package com.migration.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.TokenRange;
import com.migration.dto.MultiFileConversionResponse;

import java.util.Set;
import java.util.Locale;

public final class GeneratedArtifactValidator {

    private static final Set<String> JAVA_TYPES = Set.of(
            "CONTROLLER", "SERVICE", "REPOSITORY", "ENTITY", "DTO", "CONFIG", "CONFIGURATION",
            "SOURCE", "TEST", "MODEL", "MODEL_BEAN", "DAO", "UTIL", "UTILITY");

    private GeneratedArtifactValidator() { }

    public static MultiFileConversionResponse validate(MultiFileConversionResponse response) {
        if (response == null || response.artifacts() == null || response.artifacts().isEmpty()) {
            throw new IllegalArgumentException("La reponse IA ne contient aucun fichier genere.");
        }
        for (var artifact : response.artifacts()) {
            if (artifact == null) {
                throw new IllegalArgumentException("La reponse IA contient un artefact null.");
            }
            validateCode(artifact.relativeTargetPath(), artifact.artifactType(), artifact.code());
        }
        return response;
    }

    public static void validateCode(String path, String artifactType, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Code genere vide pour " + path + ".");
        }
        String fileName = path == null ? "" : path.replace('\\', '/').replaceAll("^.*/", "");
        boolean java = path == null || path.isBlank() || path.endsWith(".java")
                || (!fileName.contains(".") && artifactType != null
                && JAVA_TYPES.contains(artifactType.toUpperCase(Locale.ROOT)));
        if (!java) {
            return;
        }
        var parsed = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(code);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            throw new IllegalArgumentException("Code Java incomplet ou invalide pour " + path
                    + " : le champ code doit contenir le fichier transforme complet, "
                    + "pas uniquement son nom, un identifiant/package (ex. com.migrated.dto.Erreur) "
                    + "ou un chemin de fichier. "
                    + parsed.getProblems().stream().findFirst()
                    .map(problem -> formatProblem(problem, code)).orElse("Erreur de syntaxe Java."));
        }
        var unit = parsed.getResult().orElseThrow();
        boolean descriptor = path != null && ((path.endsWith("package-info.java")
                && unit.getPackageDeclaration().isPresent()) || (path.endsWith("module-info.java")
                && unit.getModule().isPresent()));
        if (unit.getTypes().isEmpty() && !descriptor) {
            throw new IllegalArgumentException("Aucune declaration de type Java dans " + path
                    + " : le champ code doit contenir le fichier transforme complet "
                    + "(classe, interface, record ou enum), pas uniquement son nom.");
        }
    }

    private static String formatProblem(Problem problem, String code) {
        String message = conciseDiagnostic(problem.getMessage());
        var rangeOpt = problem.getLocation().flatMap(TokenRange::toRange);
        if (rangeOpt.isEmpty()) {
            return message;
        }
        var begin = rangeOpt.get().begin;
        String locationPrefix = "Ligne " + begin.line + ", col " + begin.column + " : ";
        String offendingLine = code.lines()
                .skip(Math.max(0, begin.line - 1))
                .findFirst()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> " | Code : " + conciseDiagnostic(line))
                .orElse("");
        return locationPrefix + message + offendingLine;
    }

    public static String conciseDiagnostic(String message) {
        if (message == null) {
            return "";
        }
        String firstLine = message.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("");
        if (firstLine.codePointCount(0, firstLine.length()) <= 1000) {
            return firstLine;
        }
        return firstLine.substring(0, firstLine.offsetByCodePoints(0, 997)) + "...";
    }
}
