package com.migration.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.migration.model.MethodChunk;
import com.migration.model.ChunkStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Service déterministe utilisant JavaParser pour lire et analyser le code source legacy.
 */
@Service
@Slf4j
public class LegacyCodeParserService {

    @Value("${migration.source.legacy-dir}")
    private String legacyDirPath;

    @PostConstruct
    public void init() {
        log.info("Initialisation du parseur avec le répertoire legacy : {}", legacyDirPath);

        // Configuration de JavaParser pour la résolution de symboles
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        // Vous pourrez ajouter un JavaParserTypeSolver ici si besoin pour résoudre des types
        // définis dans le code legacy lui-même :
        // combinedTypeSolver.add(new JavaParserTypeSolver(new File(legacyDirPath)));

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);
    }

    /**
     * Parse un fichier Java legacy et retourne l'Abstract Syntax Tree (AST).
     *
     * @param filePath Chemin absolu vers le fichier Java.
     * @return L'unité de compilation (AST) du fichier.
     * @throws FileNotFoundException Si le fichier n'est pas trouvé.
     */
    public CompilationUnit parseJavaFile(String filePath) throws FileNotFoundException {
        log.debug("Analyse statique du fichier : {}", filePath);
        return StaticJavaParser.parse(new File(filePath));
    }

    /**
     * Recherche et parse toutes les classes Java (ex: Servlets) dans le répertoire legacy.
     *
     * @return Liste des CompilationUnits.
     */
    public List<CompilationUnit> parseAllLegacyFiles() {
        List<CompilationUnit> asts = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(Paths.get(legacyDirPath))) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> path.toString().endsWith(".java"))
                 .forEach(path -> {
                     try {
                         asts.add(parseJavaFile(path.toString()));
                     } catch (FileNotFoundException e) {
                         log.error("Erreur lors du parsing de : {}", path, e);
                     }
                 });
        } catch (Exception e) {
            log.error("Erreur lors de la lecture du répertoire legacy : {}", legacyDirPath, e);
        }

        return asts;
    }

    /**
     * Lit le contenu brut d'un fichier (ex: web.xml).
     */
    public String readRawFile(String relativePath) {
        try {
            Path path = Paths.get(legacyDirPath, relativePath);
            return Files.readString(path);
        } catch (Exception e) {
            log.error("Impossible de lire le fichier brut : {}", relativePath, e);
            return null;
        }
    }

    public boolean shouldChunk(String classContent) {
        if (classContent == null) return false;
        long lineCount = classContent.lines().count();
        return lineCount > 1500;
    }

    public String extractClassStructure(String classContent) {
        CompilationUnit cu = StaticJavaParser.parse(classContent);
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            method.removeBody();
        });
        cu.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class).forEach(c -> {
            c.setBody(new com.github.javaparser.ast.stmt.BlockStmt());
        });
        return cu.toString();
    }

    public String extractSignatures(String classContent) {
        CompilationUnit cu = StaticJavaParser.parse(classContent);
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            method.removeBody();
        });
        cu.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class).forEach(c -> {
            c.setBody(new com.github.javaparser.ast.stmt.BlockStmt());
        });
        return cu.toString();
    }

    public List<MethodChunk> extractMethods(String classContent) {
        List<MethodChunk> methodChunks = new ArrayList<>();
        CompilationUnit cu = StaticJavaParser.parse(classContent);
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            String methodName = method.getNameAsString();
            String originalSignature = method.getDeclarationAsString(true, true, true);
            String originalCode = method.toString();

            methodChunks.add(MethodChunk.builder()
                .methodName(methodName)
                .originalSignature(originalSignature)
                .originalCode(originalCode)
                .status(ChunkStatus.PENDING)
                .build());
        });
        return methodChunks;
    }

    /**
     * Détermine de manière déterministe via JavaParser si un fichier Java est trivial
     * (DTO/POJO pur, constantes uniquement, getters/setters, constructeurs simples,
     * equals/hashCode/toString) afin d'éviter un appel LLM inutile.
     *
     * @param javaSource Code source Java brut.
     * @return true si le fichier est trivial, false sinon (ou en cas d'erreur de parsing).
     */
    public boolean isTrivialJavaCode(String javaSource) {
        if (javaSource == null || javaSource.isBlank()) {
            return false;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(javaSource);
            var types = cu.getTypes();
            if (types.isEmpty()) {
                return false;
            }
            for (var typeDecl : types) {
                if (!isTrivialTypeDeclaration(typeDecl)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.debug("Parsing JavaParser impossible pour la détection de trivialité : {}", e.getMessage());
            return false;
        }
    }

    private boolean isTrivialTypeDeclaration(com.github.javaparser.ast.body.TypeDeclaration<?> typeDecl) {
        if (typeDecl.isEnumDeclaration()) {
            // Un enum est trivial s'il ne contient pas de méthodes métier (hors getters/toString)
            return typeDecl.getMethods().stream().allMatch(this::isTrivialMethod)
                    && typeDecl.getConstructors().stream().allMatch(this::isTrivialConstructor);
        }

        if (typeDecl.isClassOrInterfaceDeclaration()) {
            var cid = typeDecl.asClassOrInterfaceDeclaration();

            // Les classes qui étendent des composants web/legacy (Servlet, Struts Action, Filter...) ne sont pas triviales
            for (var ext : cid.getExtendedTypes()) {
                String extName = ext.getNameAsString();
                if (extName.endsWith("Servlet") || extName.endsWith("Action") || extName.endsWith("Filter")
                        || extName.endsWith("TagSupport") || extName.endsWith("Listener")) {
                    return false;
                }
            }

            // Vérifier les blocs d'initialisation statiques ou d'instance non triviaux
            if (!cid.findAll(com.github.javaparser.ast.body.InitializerDeclaration.class).isEmpty()) {
                return false;
            }

            // Vérifier tous les constructeurs
            for (var ctor : cid.getConstructors()) {
                if (!isTrivialConstructor(ctor)) {
                    return false;
                }
            }

            // Vérifier toutes les méthodes
            for (var method : cid.getMethods()) {
                if (!isTrivialMethod(method)) {
                    return false;
                }
            }

            // Vérifier les types imbriqués
            for (var member : cid.getMembers()) {
                if (member.isTypeDeclaration() && !isTrivialTypeDeclaration(member.asTypeDeclaration())) {
                    return false;
                }
            }

            return true;
        }

        // Records ou annotations simples
        return typeDecl.isRecordDeclaration()
                && typeDecl.getMethods().stream().allMatch(this::isTrivialMethod);
    }

    private boolean isTrivialConstructor(com.github.javaparser.ast.body.ConstructorDeclaration ctor) {
        var stmts = ctor.getBody().getStatements();
        for (var stmt : stmts) {
            if (stmt.isExplicitConstructorInvocationStmt()) {
                continue;
            }
            if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isAssignExpr()) {
                continue;
            }
            return false;
        }
        return true;
    }

    private boolean isTrivialMethod(MethodDeclaration method) {
        String name = method.getNameAsString();
        // Méthodes boilerplate standard de JavaBeans
        if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")
                || name.equals("compareTo") || name.equals("clone")) {
            return true;
        }

        // Méthodes abstraites / d'interface : si elles ne ressemblent pas à des getters/setters, ce sont des contrats métier
        if (method.getBody().isEmpty()) {
            return isGetterSignature(method) || isSetterSignature(method);
        }

        var stmts = method.getBody().get().getStatements();
        if (stmts.size() != 1) {
            return false;
        }

        var stmt = stmts.get(0);
        // Getter simple : 0 paramètre, unique ReturnStmt (accès champ / nom simple)
        if (isGetterSignature(method) && stmt.isReturnStmt()) {
            var exprOpt = stmt.asReturnStmt().getExpression();
            return exprOpt.isPresent()
                    && (exprOpt.get().isNameExpr() || exprOpt.get().isFieldAccessExpr() || exprOpt.get().isLiteralExpr());
        }

        // Setter simple : 1 paramètre, unique assignation
        if (isSetterSignature(method) && stmt.isExpressionStmt()
                && stmt.asExpressionStmt().getExpression().isAssignExpr()) {
            return true;
        }

        return false;
    }

    private boolean isGetterSignature(MethodDeclaration method) {
        String name = method.getNameAsString();
        return method.getParameters().isEmpty()
                && !method.getType().isVoidType()
                && ((name.startsWith("get") && name.length() > 3)
                    || (name.startsWith("is") && name.length() > 2));
    }

    private boolean isSetterSignature(MethodDeclaration method) {
        String name = method.getNameAsString();
        return method.getParameters().size() == 1
                && method.getType().isVoidType()
                && name.startsWith("set") && name.length() > 3;
    }

    /**
     * Extrait le nom de package d'un code source Java, ou chaîne vide si absent.
     */
    public String extractPackageName(String javaSource) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(javaSource);
            return cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        } catch (Exception e) {
            return "";
        }
    }
}
