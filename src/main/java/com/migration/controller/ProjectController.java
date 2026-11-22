package com.migration.controller;

import com.migration.dto.ScanRequest;
import com.migration.model.MigrationStatus;
import com.migration.service.SemanticPreAnalysisService;
import com.migration.service.SourceScannerService;
import com.migration.pipeline.ProjectInitializerStep;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final SourceScannerService scannerService;
    private final SemanticPreAnalysisService preAnalysisService;
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

    /**
     * Déclenche de manière asynchrone la pré-analyse sémantique assistée par LLM
     * sur tous les fichiers en statut SCANNED ou ANALYSIS_FAILED.
     */
    @PostMapping("/pre-analyze")
    public ResponseEntity<Map<String, Object>> preAnalyzeProject(
            @RequestParam(required = false) String llmModel) {
        int eligibleCount = fileStatusRepository
                .findByStatusIn(List.of(MigrationStatus.SCANNED, MigrationStatus.ANALYSIS_FAILED))
                .size();
        preAnalysisService.analyzeAllScannedFiles(llmModel);
        return ResponseEntity.accepted().body(Map.of(
                "status", "ACCEPTED",
                "filesQueued", eligibleCount
        ));
    }

    /**
     * Retourne l'avancement en temps réel de la pré-analyse sémantique (fichier courant et décompte).
     */
    @GetMapping("/pre-analyze/status")
    public ResponseEntity<Map<String, Object>> getPreAnalysisStatus() {
        return ResponseEntity.ok(preAnalysisService.getProgress());
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> resetProject() {
        fileStatusRepository.deleteAll();
        projectConfigurationRepository.deleteAll();
        return ResponseEntity.ok().build();
    }
}
