package com.migration.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.migration.core.model.ArchitecturalRole;
import com.migration.model.GeneratedArtifact;
import com.migration.model.MigrationFileStatus;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicRoutingService {

    private static final String DEFAULT_GROUP_ID = "com.migrated";
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+[^;]+;\\s*");

    private static final Map<String, String> ARTIFACT_SUBPACKAGES = Map.ofEntries(
            Map.entry("DTO", "dto"),
            Map.entry("SERVICE", "service"),
            Map.entry("ENTITY", "entity"),
            Map.entry("MODEL", "entity"),
            Map.entry("MODEL_BEAN", "entity"),
            Map.entry("CONTROLLER", "controller"),
            Map.entry("CONTROLER", "controller"),
            Map.entry("REPOSITORY", "repository"),
            Map.entry("DAO", "repository"),
            Map.entry("CONFIG", "config"),
            Map.entry("CONFIGURATION", "config"),
            Map.entry("UTIL", "util"),
            Map.entry("UTILITY", "util")
    );

    private static final Set<String> JAVA_ARTIFACT_TYPES = Set.of(
            "DTO", "SERVICE", "ENTITY", "MODEL", "MODEL_BEAN",
            "CONTROLLER", "CONTROLER", "REPOSITORY", "DAO",
            "CONFIG", "CONFIGURATION", "UTIL", "UTILITY", "SOURCE", "TEST"
    );

    private final ProjectConfigurationRepository projectConfigurationRepository;

    @Value("${migration.target.output-dir:}")
    private String fallbackOutputDir;

    /**
     * Route le code source vers le bon package en fonction du rôle architectural et sauvegarde le fichier.
     * @param sourceCode
     * @param role
     * @param className
     * @throws IOException
     */
    public void routeAndSaveFile(String sourceCode, ArchitecturalRole role, String className) throws IOException {
        ProjectConfiguration config = projectConfigurationRepository.findById(1L).orElseThrow(() -> new RuntimeException("Configuration not found"));

        String baseDir = config.getTargetBaseDir();
        String basePackage = config.getTargetBasePackage();

        if (baseDir == null || basePackage == null) {
             throw new IllegalStateException("Target baseDir and basePackage must be configured");
        }

        String subPackage = role.getDefaultSubPackage();
        String fullPackage = basePackage + "." + subPackage;

        CompilationUnit cu = StaticJavaParser.parse(sourceCode);
        cu.setPackageDeclaration(new PackageDeclaration(StaticJavaParser.parseName(fullPackage)));
        String modifiedCode = cu.toString();

        String packagePath = fullPackage.replace(".", "/");
        Path targetDirPath = Paths.get(baseDir, packagePath);
        Files.createDirectories(targetDirPath);

        Path targetFilePath = targetDirPath.resolve(className + ".java");
        Files.writeString(targetFilePath, modifiedCode);
        log.info("File saved to {}", targetFilePath);
    }

    /**
     * Sauvegarde tous les artefacts générés d'un fichier migré dans le projet cible,
     * en respectant le Group ID et le chemin du projet configurés ainsi que les sous-packages
     * par type d'artefact (dto, service, entity, controller, repository, etc.).
     *
     * @param status Statut de migration contenant les artefacts à sauvegarder.
     * @throws IOException En cas d'erreur d'écriture sur disque.
     */
    public void saveArtifacts(MigrationFileStatus status) throws IOException {
        if (status == null || status.getGeneratedArtifacts() == null || status.getGeneratedArtifacts().isEmpty()) {
            return;
        }

        ProjectConfiguration config = projectConfigurationRepository.findById(1L).orElse(null);
        Path projectRoot = resolveProjectRoot(config);
        String basePackage = resolveBasePackage(config);
        List<String> savedFiles = new ArrayList<>();

        for (GeneratedArtifact artifact : status.getGeneratedArtifacts()) {
            if (artifact == null || !isNotBlank(artifact.getGeneratedCode())) {
                continue;
            }
            Path savedPath = saveSingleArtifact(artifact, status, projectRoot, basePackage);
            artifact.setLastProcessedAt(java.time.LocalDateTime.now());
            artifact.setCompilationStatus("PENDING");
            artifact.setLastCompilerErrors(null);
            savedFiles.add(savedPath.toString());
        }

        if (!savedFiles.isEmpty()) {
            status.setTargetFiles(savedFiles);
        }
    }

    private Path saveSingleArtifact(GeneratedArtifact artifact,
                                    MigrationFileStatus status,
                                    Path projectRoot,
                                    String basePackage) throws IOException {
        String type = artifact.getArtifactType() != null
                ? artifact.getArtifactType().trim().toUpperCase(Locale.ROOT)
                : "";
        String rawRelPath = artifact.getRelativeTargetPath() != null
                ? artifact.getRelativeTargetPath().trim().replace("\\", "/")
                : "";
        String code = artifact.getGeneratedCode();

        boolean isJava = rawRelPath.endsWith(".java") || JAVA_ARTIFACT_TYPES.contains(type);
        if (!isJava) {
            return saveNonJavaArtifact(artifact, status, projectRoot, type, rawRelPath, code);
        }

        boolean isTest = "TEST".equals(type) || rawRelPath.startsWith("src/test/java/");
        String fileName = resolveJavaFileName(rawRelPath, code, status.getSourceFile());
        String subPackage = resolveSubPackage(type, rawRelPath, fileName, code);
        String fullPackage = subPackage.isEmpty() ? basePackage : basePackage + "." + subPackage;

        String updatedCode = updatePackageDeclaration(code, fullPackage);
        Path sourceRoot = projectRoot.resolve(isTest ? "src/test/java" : "src/main/java");
        Path targetDir = sourceRoot.resolve(fullPackage.replace('.', '/')).normalize();
        Path targetFile = targetDir.resolve(fileName).normalize();

        if (!targetFile.startsWith(projectRoot)) {
            throw new SecurityException("Chemin cible hors du répertoire du projet : " + targetFile);
        }

        Files.createDirectories(targetDir);
        Files.writeString(targetFile, updatedCode, StandardCharsets.UTF_8);

        artifact.setRelativeTargetPath(projectRoot.relativize(targetFile).toString().replace('\\', '/'));
        artifact.setGeneratedCode(updatedCode);
        artifact.setValidated(true);
        log.info("Artefact {} sauvegardé dans {}", type.isEmpty() ? "JAVA" : type, targetFile);
        return targetFile;
    }

    private Path saveNonJavaArtifact(GeneratedArtifact artifact,
                                     MigrationFileStatus status,
                                     Path projectRoot,
                                     String type,
                                     String rawRelPath,
                                     String code) throws IOException {
        String relPath = rawRelPath;
        while (relPath.startsWith("/")) {
            relPath = relPath.substring(1);
        }
        if (relPath.isEmpty()) {
            relPath = "src/main/resources/" + status.getSourceFile();
        } else if ("VIEW".equals(type) && !relPath.startsWith("src/")) {
            relPath = "src/main/resources/templates/" + relPath;
        }

        Path targetFile = projectRoot.resolve(relPath).normalize();
        if (!targetFile.startsWith(projectRoot)) {
            throw new SecurityException("Chemin cible hors du répertoire du projet : " + relPath);
        }

        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, code, StandardCharsets.UTF_8);
        artifact.setRelativeTargetPath(projectRoot.relativize(targetFile).toString().replace('\\', '/'));
        artifact.setValidated(true);
        log.info("Artefact {} sauvegardé dans {}", type.isEmpty() ? "RESOURCE" : type, targetFile);
        return targetFile;
    }

    private Path resolveProjectRoot(ProjectConfiguration config) {
        if (config != null && isNotBlank(config.getTargetProjectPath())) {
            return Paths.get(config.getTargetProjectPath().trim()).toAbsolutePath().normalize();
        }
        if (isNotBlank(fallbackOutputDir)) {
            return Paths.get(fallbackOutputDir.trim()).toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Le chemin du projet destination (targetProjectPath) n'est pas configuré.");
    }

    private String resolveBasePackage(ProjectConfiguration config) {
        String candidate = null;
        if (config != null && isNotBlank(config.getTargetGroupId())) {
            candidate = config.getTargetGroupId().trim();
        } else if (config != null && isNotBlank(config.getTargetBasePackage())) {
            candidate = config.getTargetBasePackage().trim();
        }
        if (!isNotBlank(candidate)) {
            return DEFAULT_GROUP_ID;
        }
        String sanitized = candidate.replace('-', '_').replaceAll("^\\.+|\\.+$", "");
        return sanitized.isEmpty() ? DEFAULT_GROUP_ID : sanitized;
    }

    private String resolveSubPackage(String type, String rawRelPath, String fileName, String code) {
        String mapped = ARTIFACT_SUBPACKAGES.get(type);
        if (mapped != null) {
            return mapped;
        }

        String lowerPath = rawRelPath.toLowerCase(Locale.ROOT);
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerPath.contains("/dto/") || lowerName.endsWith("dto.java") || lowerName.endsWith("dtotest.java")) {
            return "dto";
        }
        if (lowerPath.contains("/service/") || lowerName.endsWith("service.java") || lowerName.endsWith("serviceimpl.java") || lowerName.endsWith("servicetest.java") || code.contains("@Service")) {
            return "service";
        }
        if (lowerPath.contains("/entity/") || lowerName.endsWith("entity.java") || lowerName.endsWith("entitytest.java") || code.contains("@Entity")) {
            return "entity";
        }
        if (lowerPath.contains("/controller/") || lowerName.endsWith("controller.java") || lowerName.endsWith("controllertest.java") || code.contains("@RestController") || code.contains("@Controller")) {
            return "controller";
        }
        if (lowerPath.contains("/repository/") || lowerName.endsWith("repository.java") || lowerName.endsWith("dao.java") || lowerName.endsWith("repositorytest.java") || code.contains("@Repository")) {
            return "repository";
        }
        if (lowerPath.contains("/config/") || lowerName.endsWith("config.java") || code.contains("@Configuration")) {
            return "config";
        }
        return "";
    }

    private String resolveJavaFileName(String rawRelPath, String code, String fallbackSourceFile) {
        if (isNotBlank(rawRelPath)) {
            int lastSlash = rawRelPath.lastIndexOf('/');
            String candidate = lastSlash >= 0 ? rawRelPath.substring(lastSlash + 1) : rawRelPath;
            if (isNotBlank(candidate)) {
                return candidate.endsWith(".java") ? candidate : candidate + ".java";
            }
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);
            if (!cu.getTypes().isEmpty()) {
                return cu.getTypes().get(0).getNameAsString() + ".java";
            }
        } catch (Exception ignored) {
            // Fallback below
        }
        String fallback = isNotBlank(fallbackSourceFile) ? fallbackSourceFile : "GeneratedClass.java";
        return fallback.endsWith(".java") ? fallback : fallback + ".java";
    }

    private String updatePackageDeclaration(String code, String fullPackage) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);
            cu.setPackageDeclaration(new PackageDeclaration(StaticJavaParser.parseName(fullPackage)));
            return cu.toString();
        } catch (Exception e) {
            String replacement = "package " + fullPackage + ";\n";
            if (PACKAGE_PATTERN.matcher(code).find()) {
                return PACKAGE_PATTERN.matcher(code).replaceFirst(replacement + "\n");
            }
            return replacement + "\n" + code;
        }
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
