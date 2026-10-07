package com.migration.service;

import com.migration.ai.MigrationAiService;
import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.repository.MigrationFileStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncMigrationRetryService {

    private final MigrationFileStatusRepository repository;
    private final MigrationAiService aiService;

    @Async
    public void convertFile(Long fileId, String customPrompt) {
        MigrationFileStatus status = repository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid file ID: " + fileId));

        status.setStatus(MigrationStatus.CONVERTING);
        status.setErrorMessage(null);
        repository.save(status);

        try {
            Path filePath = Paths.get(status.getAbsolutePath());
            String userMessage;
            if (Files.exists(filePath)) {
                userMessage = Files.readString(filePath);
            } else {
                log.warn("File not found on disk, using dummy content. Path: {}", status.getAbsolutePath());
                userMessage = "Dummy content for " + status.getSourceFile();
            }

            String systemPrompt = "Tu es un développeur Java expert. Récris le code legacy J2EE suivant en une classe Spring Boot moderne.";
            String additionalInstructions = null;
            if (customPrompt != null && !customPrompt.isEmpty()) {
                additionalInstructions = "Ceci est une correction d'une tentative précédente. Suis STRICTEMENT les instructions suivantes : " + customPrompt;
            }

            String response = aiService.analyzeAndRefactor(systemPrompt, Map.of(), userMessage, additionalInstructions);

            log.info("Converted file {} successfully. Response length: {}", status.getSourceFile(), response.length());

            status.setStatus(MigrationStatus.NEEDS_REVIEW);
            status.setConverted(true);
            repository.save(status);
        } catch (Exception e) {
            log.error("Error during conversion for file {}", status.getSourceFile(), e);
            status.setStatus(MigrationStatus.ERROR);
            status.setErrorMessage(e.getMessage());
            repository.save(status);
        }
    }
}
