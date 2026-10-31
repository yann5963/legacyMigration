package com.migration.service;

import com.migration.model.InitializrRequest;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.ProjectConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringInitializrServiceTest {

    @Mock
    private ProjectConfigurationRepository configRepository;

    @InjectMocks
    private SpringInitializrService initializrService;

    @Test
    void shouldThrowExceptionWhenTargetDirectoryIsNotEmpty(@TempDir Path tempDir) throws Exception {
        // Arrange
        Files.createFile(tempDir.resolve("some-file.txt"));

        ProjectConfiguration config = new ProjectConfiguration();
        config.setTargetProjectPath(tempDir.toString());
        when(configRepository.findById(1L)).thenReturn(Optional.of(config));

        InitializrRequest request = new InitializrRequest();
        request.setDependencies(List.of("web"));

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            initializrService.generateProject(request);
        });
        assertTrue(exception.getMessage().contains("n'est pas vide"));
    }

    @Test
    void shouldThrowExceptionWhenPathIsMissing() {
        // Arrange
        ProjectConfiguration config = new ProjectConfiguration();
        config.setTargetProjectPath(null); // Missing path
        when(configRepository.findById(1L)).thenReturn(Optional.of(config));

        InitializrRequest request = new InitializrRequest();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            initializrService.generateProject(request);
        });
        assertTrue(exception.getMessage().contains("n'est pas configuré"));
    }
}
