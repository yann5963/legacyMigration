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
}
