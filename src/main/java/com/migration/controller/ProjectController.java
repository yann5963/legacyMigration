package com.migration.controller;

import com.migration.dto.ScanRequest;
import com.migration.service.SourceScannerService;
import com.migration.pipeline.ProjectInitializerStep;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.migration.model.ProjectConfiguration;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final SourceScannerService scannerService;
    private final ProjectInitializerStep initializerStep;
    private final MigrationFileStatusRepository fileStatusRepository;
    private final ProjectConfigurationRepository projectConfigurationRepository;

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Integer>> scanProject(@RequestBody ScanRequest request) {
        initializerStep.execute();

        projectConfigurationRepository.findById(1L).ifPresent(config -> {
            config.setDefaultLlmModel(request.getDefaultLlmModel());
            projectConfigurationRepository.save(config);
        });

        int count = scannerService.scanProject(request.getSourceRootPath());
        return ResponseEntity.ok(Map.of("filesFound", count));
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> resetProject() {
        fileStatusRepository.deleteAll();
        projectConfigurationRepository.deleteAll();
        return ResponseEntity.ok().build();
    }
}
