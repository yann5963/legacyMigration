package com.migration.service;

import com.migration.ai.MigrationAiService;
import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.repository.MigrationFileStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncMigrationRetryService {

    private final MigrationFileStatusRepository repository;
    private final MigrationAiService aiService;

    @Async
    public void retryMigration(Long fileId, String customPrompt) {
        MigrationFileStatus status = repository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid file ID: " + fileId));

        status.setStatus(MigrationStatus.IN_PROGRESS);
        status.setErrorMessage(null);
        repository.save(status);

        try {
            // Simulated re-run logic. In a full implementation, you'd re-invoke the specific MigrationStep
            // or logic that originally processed this file, passing the custom prompt.
            // Here, we just call the aiService to demonstrate the prompt injection.

            String systemPrompt = "Tu es un développeur Java expert. Récris le code legacy J2EE suivant en une classe Spring Boot moderne. Le fichier est : {sourceFile}";
            // using dummy context just to simulate
            String userMessage = "Code of " + status.getSourceFile();

            String response = aiService.analyzeAndRefactor(systemPrompt, Map.of("sourceFile", status.getSourceFile()), userMessage, customPrompt);

            log.info("Retried file {} successfully. Response length: {}", status.getSourceFile(), response.length());

            status.setStatus(MigrationStatus.SUCCESS);
            repository.save(status);
        } catch (Exception e) {
            log.error("Error during retry for file {}", status.getSourceFile(), e);
            status.setStatus(MigrationStatus.ERROR);
            status.setErrorMessage(e.getMessage());
            repository.save(status);
        }
    }
}
