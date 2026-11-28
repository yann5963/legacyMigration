package com.migration.controller;

import com.migration.service.ProjectFinalizationJobService;
import com.migration.service.ProjectFinalizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/migration/finalization")
@RequiredArgsConstructor
public class ProjectFinalizationController {

    private final ProjectFinalizationService finalization;
    private final ProjectFinalizationJobService jobs;

    public record FixRequest(String customPrompt, String modelOverride) { }

    @GetMapping
    public ProjectFinalizationService.BuildSummary summary() {
        return finalization.getSummary();
    }

    @GetMapping("/status")
    public ProjectFinalizationJobService.JobStatus status() {
        return jobs.getStatus();
    }

    @PostMapping("/prepare")
    public ResponseEntity<ProjectFinalizationJobService.JobStatus> prepare() {
        return ResponseEntity.accepted().body(jobs.start("PREPARE", 1, null, null, null));
    }

    @PostMapping("/build")
    public ResponseEntity<ProjectFinalizationJobService.JobStatus> build() {
        return ResponseEntity.accepted().body(jobs.start("BUILD", 1, null, null, null));
    }

    @PostMapping("/auto-fix")
    public ResponseEntity<ProjectFinalizationJobService.JobStatus> autoFix(
            @RequestParam(defaultValue = "10") int maxIterations) {
        return ResponseEntity.accepted().body(jobs.start("AUTO", maxIterations, null, null, null));
    }

    @GetMapping("/artifacts/{id}/fix-prompt")
    public ProjectFinalizationService.PromptPreview fixPrompt(@PathVariable Long id) {
        return finalization.getFixPrompt(id);
    }

    @PostMapping("/artifacts/{id}/fix")
    public ResponseEntity<ProjectFinalizationJobService.JobStatus> fix(@PathVariable Long id,
            @RequestBody(required = false) FixRequest request) {
        return ResponseEntity.accepted().body(jobs.start("FIX", 1, id,
                request == null ? null : request.customPrompt(),
                request == null ? null : request.modelOverride()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }
}
