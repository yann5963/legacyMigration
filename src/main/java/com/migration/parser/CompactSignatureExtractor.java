package com.migration.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extrait d'un code Java généré son FQCN, sa table SQL (@Table) et une signature compacte
 * (annotations, champs et signatures publiques sans corps) destinée à être injectée dans les prompts.
 */
@Component
public class CompactSignatureExtractor {

    static final int MAX_SIGNATURE_CHARS = 4000;

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE_PATTERN =
            Pattern.compile("(?m)^[^\\n/*]*?\\b(?:class|interface|record|enum)\\s+([A-Za-z_]\\w*)");
    private static final Pattern TABLE_PATTERN =
            Pattern.compile("@Table\\s*\\([^)]*?\\bname\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern FALLBACK_LINE_PATTERN = Pattern.compile(
            "^\\s*(@\\w+.*|(?:public|protected|private|static|final|abstract|sealed|\\s)*"
                    + "(?:class|interface|record|enum)\\s+\\w+.*|"
                    + "(?:private|protected|public)\\s+[^=(;]+(?:=.*)?;|"
                    + "public\\s+[^=;]+\\)\\s*(?:throws\\s+[^{]+)?\\{?)\\s*$");

    public record Extraction(String fqcn, String sqlTableName, String compactSignature,
                             List<String> fieldNames, List<String> methodNames) {
    }

    public Extraction extract(String code, String artifactType, String relativeTargetPath) {
        if (code == null || code.isBlank()) {
            return new Extraction(fqcnFromPath(relativeTargetPath), null, "", List.of(), List.of());
        }
        try {
            ParserConfiguration configuration = new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            ParseResult<CompilationUnit> result = new JavaParser(configuration).parse(code);
            Optional<CompilationUnit> unit = result.getResult();
            if (result.isSuccessful() && unit.isPresent()) {
                Extraction extraction = fromAst(unit.get(), artifactType, relativeTargetPath);
                if (extraction != null) {
                    return extraction;
                }
            }
        } catch (RuntimeException ignored) {
            // bascule sur l'extraction par expressions régulières
        }
        return fallback(code, artifactType, relativeTargetPath);
    }

    private Extraction fromAst(CompilationUnit unit, String artifactType, String relativeTargetPath) {
        if (unit.getTypes().isEmpty()) {
            return null;
        }
        String expectedName = simpleNameFromPath(relativeTargetPath);
        TypeDeclaration<?> type = unit.getTypes().stream()
                .filter(t -> t.getNameAsString().equals(expectedName))
                .findFirst()
                .orElse(unit.getType(0));
        String packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String fqcn = packageName.isEmpty() ? type.getNameAsString() : packageName + "." + type.getNameAsString();

        StringBuilder sb = new StringBuilder();
        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        for (AnnotationExpr annotation : type.getAnnotations()) {
            sb.append(annotation).append('\n');
        }
        sb.append(typeHeader(type)).append(" {\n");

        List<String> fieldNames = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();
        boolean isInterface = type instanceof ClassOrInterfaceDeclaration c && c.isInterface();

        if (type instanceof EnumDeclaration enumDeclaration) {
            sb.append("  ").append(enumDeclaration.getEntries().stream()
                    .map(e -> e.getNameAsString()).collect(Collectors.joining(", "))).append(";\n");
        }
        if (type instanceof RecordDeclaration record) {
            record.getParameters().forEach(p -> fieldNames.add(p.getNameAsString()));
        }
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                if (field.isStatic() && field.isFinal()) {
                    continue;
                }
                appendField(sb, field);
                field.getVariables().forEach(v -> fieldNames.add(v.getNameAsString()));
            } else if (member instanceof MethodDeclaration method) {
                if (!method.isPublic() && !isInterface) {
                    continue;
                }
                appendMethod(sb, method);
                methodNames.add(method.getNameAsString());
            }
        }
        sb.append("}\n");

        String table = "ENTITY".equalsIgnoreCase(artifactType) || artifactType == null
                ? tableName(type).orElse(null)
                : null;
        return new Extraction(fqcn, table, truncate(sb.toString()), fieldNames, methodNames);
    }

    private String typeHeader(TypeDeclaration<?> type) {
        StringBuilder header = new StringBuilder();
        type.getModifiers().forEach(m -> header.append(m.getKeyword().asString()).append(' '));
        if (type instanceof RecordDeclaration record) {
            header.append("record ").append(record.getNameAsString()).append('(')
                    .append(record.getParameters().stream().map(this::parameter).collect(Collectors.joining(", ")))
                    .append(')');
            if (!record.getImplementedTypes().isEmpty()) {
                header.append(" implements ").append(record.getImplementedTypes().stream()
                        .map(Object::toString).collect(Collectors.joining(", ")));
            }
        } else if (type instanceof EnumDeclaration) {
            header.append("enum ").append(type.getNameAsString());
        } else if (type instanceof ClassOrInterfaceDeclaration declaration) {
            header.append(declaration.isInterface() ? "interface " : "class ").append(declaration.getNameAsString());
            if (!declaration.getExtendedTypes().isEmpty()) {
                header.append(" extends ").append(declaration.getExtendedTypes().stream()
                        .map(Object::toString).collect(Collectors.joining(", ")));
            }
            if (!declaration.getImplementedTypes().isEmpty()) {
                header.append(" implements ").append(declaration.getImplementedTypes().stream()
                        .map(Object::toString).collect(Collectors.joining(", ")));
            }
        } else {
            header.append(type.getNameAsString());
        }
        return header.toString();
    }

    private void appendField(StringBuilder sb, FieldDeclaration field) {
        sb.append("  ").append(inlineAnnotations(field));
        field.getModifiers().stream().map(Modifier::getKeyword).filter(k -> k != Modifier.Keyword.TRANSIENT)
                .forEach(k -> sb.append(k.asString()).append(' '));
        field.getVariables().forEach(v -> sb.append(v.getType()).append(' ').append(v.getNameAsString()).append(", "));
        sb.setLength(sb.length() - 2);
        sb.append(";\n");
    }

    private void appendMethod(StringBuilder sb, MethodDeclaration method) {
        sb.append("  ").append(inlineAnnotations(method));
        if (method.isPublic()) {
            sb.append("public ");
        }
        sb.append(method.getType()).append(' ').append(method.getNameAsString()).append('(')
                .append(method.getParameters().stream().map(this::parameter).collect(Collectors.joining(", ")))
                .append(')');
        if (!method.getThrownExceptions().isEmpty()) {
            sb.append(" throws ").append(method.getThrownExceptions().stream()
                    .map(Object::toString).collect(Collectors.joining(", ")));
        }
        sb.append(";\n");
    }

    private String parameter(Parameter parameter) {
        return inlineAnnotations(parameter) + parameter.getType() + " " + parameter.getNameAsString();
    }

    private String inlineAnnotations(NodeWithAnnotations<?> node) {
        StringBuilder sb = new StringBuilder();
        node.getAnnotations().forEach(a -> sb.append(a).append(' '));
        return sb.toString();
    }

    private Optional<String> tableName(TypeDeclaration<?> type) {
        for (AnnotationExpr annotation : type.getAnnotations()) {
            if (!"Table".equals(annotation.getNameAsString())) {
                continue;
            }
            if (annotation instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if ("name".equals(pair.getNameAsString()) && pair.getValue() instanceof StringLiteralExpr literal) {
                        return Optional.of(literal.asString());
                    }
                }
            } else if (annotation instanceof SingleMemberAnnotationExpr single
                    && single.getMemberValue() instanceof StringLiteralExpr literal) {
                return Optional.of(literal.asString());
            }
        }
        return Optional.empty();
    }

    private Extraction fallback(String code, String artifactType, String relativeTargetPath) {
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(code);
        String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
        Matcher typeMatcher = TYPE_PATTERN.matcher(code);
        String simpleName = typeMatcher.find() ? typeMatcher.group(1) : simpleNameFromPath(relativeTargetPath);
        String fqcn = simpleName == null || simpleName.isEmpty() ? fqcnFromPath(relativeTargetPath)
                : packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

        StringBuilder sb = new StringBuilder();
        List<String> fieldNames = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();
        for (String line : code.split("\\R")) {
            if (!FALLBACK_LINE_PATTERN.matcher(line).matches()) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.endsWith("{")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim() + ";";
            }
            sb.append(trimmed).append('\n');
            if (trimmed.matches("(?:private|protected|public)\\s+[^=(;]+;")) {
                String[] tokens = trimmed.substring(0, trimmed.length() - 1).trim().split("\\s+");
                fieldNames.add(tokens[tokens.length - 1]);
            }
        }
        String table = null;
        if (artifactType == null || "ENTITY".equalsIgnoreCase(artifactType)) {
            Matcher tableMatcher = TABLE_PATTERN.matcher(code);
            table = tableMatcher.find() ? tableMatcher.group(1) : null;
        }
        return new Extraction(fqcn, table, truncate(sb.toString()), fieldNames, methodNames);
    }

    private String truncate(String signature) {
        if (signature.length() <= MAX_SIGNATURE_CHARS) {
            return signature;
        }
        return signature.substring(0, MAX_SIGNATURE_CHARS) + "\n// ... tronqué\n";
    }

    private String simpleNameFromPath(String relativeTargetPath) {
        if (relativeTargetPath == null || relativeTargetPath.isBlank()) {
            return "";
        }
        String normalized = relativeTargetPath.replace('\\', '/');
        String file = normalized.substring(normalized.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
    }

    private String fqcnFromPath(String relativeTargetPath) {
        if (relativeTargetPath == null) {
            return null;
        }
        String normalized = relativeTargetPath.replace('\\', '/');
        int marker = normalized.indexOf("src/main/java/");
        if (marker < 0 || !normalized.endsWith(".java")) {
            return null;
        }
        return normalized.substring(marker + "src/main/java/".length(), normalized.length() - 5).replace('/', '.');
    }
}
