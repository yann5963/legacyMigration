package com.migration.service;

import com.migration.core.model.ArchitecturalRole;
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
import java.util.Optional;

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
}
