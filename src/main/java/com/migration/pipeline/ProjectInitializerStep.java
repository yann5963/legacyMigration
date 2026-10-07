package com.migration.pipeline;

import com.migration.model.ProjectConfiguration;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectInitializerStep implements MigrationStep {

    private final ProjectConfigurationRepository repository;

    @Value("${migration.target.output-dir}")
    private String outputDir;

    @Override
    public void execute() {
        log.info("--- Démarrage de l'initialisation du projet ---");

        ProjectConfiguration config = repository.findById(1L).orElseGet(() -> {
            log.warn("Aucune configuration trouvée, utilisation des valeurs par défaut pour l'initialisation.");
            return ProjectConfiguration.builder()
                    .targetJavaVersion("19")
                    .targetSpringBootVersion("3.2.4")
                    .targetGroupId("com.migrated")
                    .targetArtifactId("app-moderne")
                    .build();
        });

        try {
            createDirectoryStructure(config);
            generatePomXml(config);
        } catch (IOException e) {
            log.error("Erreur lors de l'initialisation du projet cible : ", e);
            throw new RuntimeException("Échec de l'initialisation du projet", e);
        }

        log.info("--- Fin de l'initialisation du projet ---");
    }

    private void createDirectoryStructure(ProjectConfiguration config) throws IOException {
        String basePackagePath = config.getTargetGroupId().replace(".", "/");

        Path[] pathsToCreate = {
            Paths.get(outputDir, "src", "main", "java", basePackagePath),
            Paths.get(outputDir, "src", "main", "resources"),
            Paths.get(outputDir, "src", "test", "java", basePackagePath)
        };

        for (Path path : pathsToCreate) {
            Files.createDirectories(path);
            log.info("Dossier créé : {}", path);
        }
    }

    private void generatePomXml(ProjectConfiguration config) throws IOException {
        String pomContent = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>%s</version>
                        <relativePath/>
                    </parent>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>0.0.1-SNAPSHOT</version>

                    <properties>
                        <java.version>%s</java.version>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-jpa</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                    </dependencies>
                </project>
                """,
                config.getTargetSpringBootVersion(),
                config.getTargetGroupId(),
                config.getTargetArtifactId(),
                config.getTargetJavaVersion()
        );

        Path pomPath = Paths.get(outputDir, "pom.xml");
        Files.writeString(pomPath, pomContent);
        log.info("Fichier pom.xml généré avec succès dans : {}", pomPath);
    }

    @Override
    public String getStepName() {
        return "Project Initialization (Folders & pom.xml)";
    }
}
