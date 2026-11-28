package com.migration.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
public class ProjectFinalizationJobService {

    private final ProjectFinalizationService finalization;
    private final TaskExecutor executor;
    private volatile JobStatus status = new JobStatus(null, false, "IDLE", "", null, null);

    public ProjectFinalizationJobService(ProjectFinalizationService finalization,
                                        @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.finalization = finalization;
        this.executor = executor;
    }

    public record JobStatus(String id, boolean running, String operation, String message,
                            String error, LocalDateTime updatedAt) { }

    public JobStatus getStatus() {
        return status;
    }

    public synchronized JobStatus start(String operation, int maxIterations, Long artifactId,
                                        String customPrompt, String modelOverride) {
        if (status.running()) {
            throw new IllegalStateException("Une operation de finalisation est deja en cours.");
        }
        if (!java.util.Set.of("PREPARE", "BUILD", "AUTO", "FIX").contains(operation)) {
            throw new IllegalArgumentException("Operation inconnue.");
        }
        if (maxIterations < 1 || maxIterations > 50) {
            throw new IllegalArgumentException("maxIterations doit etre compris entre 1 et 50.");
        }
        if ("FIX".equals(operation)) {
            finalization.getFixPrompt(artifactId);
        }
        String id = UUID.randomUUID().toString();
        update(id, true, operation, "Demarrage", null);
        JobStatus accepted = status;
        try {
            executor.execute(() -> {
                try {
                    String message = switch (operation) {
                        case "PREPARE" -> {
                            finalization.prepareTargetProject();
                            yield "Preparation terminee.";
                        }
                        case "BUILD" -> {
                            var result = finalization.runMavenBuildAndAnalyzeErrors();
                            yield Boolean.TRUE.equals(result.buildSuccess()) ? "BUILD SUCCESS" : "BUILD FAILED";
                        }
                        case "AUTO" -> {
                            var result = finalization.runStepByStepAutoFix(maxIterations,
                                    progress -> update(id, true, operation, progress, null));
                            yield Boolean.TRUE.equals(result.buildSuccess()) ? "BUILD SUCCESS"
                                    : "Arret automatique : " + result.filesInErrorCount()
                                    + " fichier(s) en erreur. Consultez le diagnostic et les fichiers a revoir.";
                        }
                        case "FIX" -> finalization.fixSingleArtifact(artifactId, customPrompt, modelOverride).message();
                        default -> throw new IllegalArgumentException("Operation inconnue.");
                    };
                    update(id, false, operation, message, null);
                } catch (Exception ex) {
                    log.error("Operation de finalisation {} en echec", operation, ex);
                    update(id, false, operation, "Operation interrompue.", ex.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            update(id, false, operation, "Demarrage impossible.", ex.getMessage());
            throw ex;
        }
        return accepted;
    }

    private void update(String id, boolean running, String operation, String message, String error) {
        status = new JobStatus(id, running, operation, message, error, LocalDateTime.now());
    }
}
