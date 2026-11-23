package com.migration.service;

import com.migration.core.model.ArchitecturalRole;
import com.migration.model.GeneratedArtifact;
import com.migration.model.MigrationFileStatus;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.ProjectConfigurationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

public class DynamicRoutingServiceTest {

    @Mock
    private ProjectConfigurationRepository projectConfigurationRepository;

    @InjectMocks
    private DynamicRoutingService dynamicRoutingService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testRouteAndSaveFile() throws IOException {
        ProjectConfiguration config = new ProjectConfiguration();
        config.setId(1L);
        config.setTargetBaseDir(tempDir.toString());
        config.setTargetBasePackage("com.example.test");
        when(projectConfigurationRepository.findById(1L)).thenReturn(Optional.of(config));

        String sourceCode = "public class MyService {\n}";
        dynamicRoutingService.routeAndSaveFile(sourceCode, ArchitecturalRole.SERVICE, "MyService");

        Path expectedFilePath = tempDir.resolve("com/example/test/service/MyService.java");
        assertTrue(Files.exists(expectedFilePath));
        String content = Files.readString(expectedFilePath);
        assertTrue(content.contains("package com.example.test.service;"));
        assertTrue(content.contains("class MyService"));
    }

    @Test
    void testSaveArtifacts_routesAllLayersWithConfiguredGroupId() throws IOException {
        ProjectConfiguration config = ProjectConfiguration.builder()
                .id(1L)
                .targetGroupId("com.acme.school")
                .targetProjectPath(tempDir.toString())
                .build();
        when(projectConfigurationRepository.findById(1L)).thenReturn(Optional.of(config));

        GeneratedArtifact dto = GeneratedArtifact.builder()
                .artifactType("DTO")
                .relativeTargetPath("src/main/java/com/old/AnneeScolDto.java")
                .generatedCode("package com.old;\npublic record AnneeScolDto(Long id) {}")
                .build();
        GeneratedArtifact service = GeneratedArtifact.builder()
                .artifactType("SERVICE")
                .relativeTargetPath("src/main/java/com/old/AnneeScolService.java")
                .generatedCode("package com.old;\npublic class AnneeScolService {}")
                .build();
        GeneratedArtifact entity = GeneratedArtifact.builder()
                .artifactType("ENTITY")
                .relativeTargetPath("src/main/java/com/old/AnneeScolEntity.java")
                .generatedCode("package com.old;\npublic class AnneeScolEntity {}")
                .build();
        GeneratedArtifact controller = GeneratedArtifact.builder()
                .artifactType("CONTROLLER")
                .relativeTargetPath("src/main/java/com/old/AnneeScolController.java")
                .generatedCode("package com.old;\npublic class AnneeScolController {}")
                .build();
        GeneratedArtifact repository = GeneratedArtifact.builder()
                .artifactType("REPOSITORY")
                .relativeTargetPath("src/main/java/com/old/AnneeScolRepository.java")
                .generatedCode("package com.old;\npublic interface AnneeScolRepository {}")
                .build();
        GeneratedArtifact testArtifact = GeneratedArtifact.builder()
                .artifactType("TEST")
                .relativeTargetPath("src/test/java/com/old/AnneeScolServiceTest.java")
                .generatedCode("package com.old;\nclass AnneeScolServiceTest {}")
                .build();

        MigrationFileStatus status = MigrationFileStatus.builder()
                .sourceFile("AnneeScolAction.java")
                .generatedArtifacts(List.of(dto, service, entity, controller, repository, testArtifact))
                .build();

        dynamicRoutingService.saveArtifacts(status);

        Path dtoPath = tempDir.resolve("src/main/java/com/acme/school/dto/AnneeScolDto.java");
        Path servicePath = tempDir.resolve("src/main/java/com/acme/school/service/AnneeScolService.java");
        Path entityPath = tempDir.resolve("src/main/java/com/acme/school/entity/AnneeScolEntity.java");
        Path controllerPath = tempDir.resolve("src/main/java/com/acme/school/controller/AnneeScolController.java");
        Path repositoryPath = tempDir.resolve("src/main/java/com/acme/school/repository/AnneeScolRepository.java");
        Path testPath = tempDir.resolve("src/test/java/com/acme/school/service/AnneeScolServiceTest.java");

        assertTrue(Files.exists(dtoPath));
        assertTrue(Files.exists(servicePath));
        assertTrue(Files.exists(entityPath));
        assertTrue(Files.exists(controllerPath));
        assertTrue(Files.exists(repositoryPath));
        assertTrue(Files.exists(testPath));

        assertTrue(Files.readString(dtoPath).contains("package com.acme.school.dto;"));
        assertTrue(Files.readString(servicePath).contains("package com.acme.school.service;"));
        assertTrue(Files.readString(entityPath).contains("package com.acme.school.entity;"));
        assertTrue(Files.readString(controllerPath).contains("package com.acme.school.controller;"));
        assertTrue(Files.readString(repositoryPath).contains("package com.acme.school.repository;"));
        assertTrue(Files.readString(testPath).contains("package com.acme.school.service;"));

        assertEquals("src/main/java/com/acme/school/dto/AnneeScolDto.java", dto.getRelativeTargetPath());
        assertEquals(6, status.getTargetFiles().size());
    }
}
