package com.migration.controller;

import com.migration.core.model.TargetProjectConfig;
import com.migration.model.ProjectConfiguration;
import com.migration.core.model.AiConfigProperties;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/configuration")
@RequiredArgsConstructor
public class ConfigurationController {

    private final ProjectConfigurationRepository repository;
    private final TargetProjectConfig targetProjectConfig;
    private final AiConfigProperties aiConfigProperties;

    /**
     * Récupère la configuration actuelle du projet.
     * @return
     */
    @GetMapping
    public ProjectConfiguration getConfiguration() {
        return repository.findById(1L).orElseGet(() -> {
            ProjectConfiguration defaultConfig = ProjectConfiguration.builder()
                    .id(1L)
                    .targetJavaVersion("19")
                    .targetSpringBootVersion("3.2.4")
                    .targetGroupId("com.migrated")
                    .targetArtifactId("app-moderne")
                    .targetBaseDir(targetProjectConfig.getBaseDir())
                    .targetBasePackage(targetProjectConfig.getBasePackage())
                    .build();
            return repository.save(defaultConfig);
        });
    }

    /**
     * Met à jour la configuration du projet avec les nouvelles valeurs fournies.
     * @param config
     * @return
     */
    @PostMapping
    public ResponseEntity<ProjectConfiguration> updateConfiguration(@RequestBody ProjectConfiguration config) {
        config.setId(1L); // Ensure we only have one configuration record
        ProjectConfiguration savedConfig = repository.save(config);
        return ResponseEntity.ok(savedConfig);
    }

    /**
     * Récupère la liste des modèles disponibles pour l'IA.
     * @return
     */
    @GetMapping("/models")
    public List<String> getAvailableModels() {
        return aiConfigProperties.getAvailableModels();
    }
}
