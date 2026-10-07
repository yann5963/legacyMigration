package com.migration.controller;

import com.migration.model.ProjectConfiguration;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/configuration")
@RequiredArgsConstructor
public class ConfigurationController {

    private final ProjectConfigurationRepository repository;

    @GetMapping
    public ProjectConfiguration getConfiguration() {
        return repository.findById(1L).orElseGet(() -> {
            ProjectConfiguration defaultConfig = ProjectConfiguration.builder()
                    .id(1L)
                    .targetJavaVersion("19")
                    .targetSpringBootVersion("3.2.4")
                    .targetGroupId("com.migrated")
                    .targetArtifactId("app-moderne")
                    .build();
            return repository.save(defaultConfig);
        });
    }

    @PostMapping
    public ResponseEntity<ProjectConfiguration> updateConfiguration(@RequestBody ProjectConfiguration config) {
        config.setId(1L); // Ensure we only have one configuration record
        ProjectConfiguration savedConfig = repository.save(config);
        return ResponseEntity.ok(savedConfig);
    }
}
