package com.migration.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.migration.core.model.ArchitecturalRole;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicRoutingService {

    private final ProjectConfigurationRepository projectConfigurationRepository;

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
}
