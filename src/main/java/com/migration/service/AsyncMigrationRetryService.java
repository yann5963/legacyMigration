package com.migration.service;

import com.migration.ai.MigrationAiService;
import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.repository.MigrationFileStatusRepository;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.document.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncMigrationRetryService {

    private final MigrationFileStatusRepository repository;
    private final MigrationAiService aiService;
    private final VectorStore vectorStore;

    @Value("${migration.target.output-dir}")
    private String outputDir;

    @Async
    public void convertFile(Long fileId, String customPrompt) {
        MigrationFileStatus status = repository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid file ID: " + fileId));

        status.setStatus(MigrationStatus.CONVERTING);
        status.setErrorMessage(null);
        if (customPrompt != null && !customPrompt.isEmpty()) {
            status.setLastCustomPrompt(customPrompt);
        }
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

            String systemPrompt = "Tu es un développeur Java expert. Récris le code legacy J2EE suivant en une classe Spring Boot moderne. Tu DOIS générer DEUX blocs de code distincts entourés par ```java ... ``` : d'abord la classe source, ensuite la classe de test JUnit 5 (avec Mockito).";

            // Query RAG for context
            try {
                List<Document> similarDocs = vectorStore.similaritySearch(SearchRequest.query(userMessage).withTopK(2));
                if (!similarDocs.isEmpty()) {
                    String context = similarDocs.stream().map(Document::getContent).collect(Collectors.joining("\n---\n"));
                    systemPrompt += "\n\nContexte lié et exemples validés précédemment :\n" + context;
                }
            } catch (Exception ex) {
                log.warn("Impossible de joindre le VectorStore pour le RAG.", ex);
            }

            String additionalInstructions = null;
            if (customPrompt != null && !customPrompt.isEmpty()) {
                additionalInstructions = "Ceci est une correction d'une tentative précédente. Suis STRICTEMENT les instructions suivantes : " + customPrompt;
            }

            String response = aiService.analyzeAndRefactor(systemPrompt, Map.of(), userMessage, additionalInstructions);

            List<String> generatedFiles = new ArrayList<>();
            String[] parts = response.split("```java");
            if (parts.length > 1) {
                String sourceCode = parts[1].split("```")[0].trim();
                String baseName = status.getSourceFile().replace(".java", "");
                Path sourceOut = Paths.get(outputDir, "src/main/java/com/migrated", baseName + ".java");
                Files.createDirectories(sourceOut.getParent());
                Files.writeString(sourceOut, sourceCode);
                generatedFiles.add(sourceOut.toString());
                if (parts.length > 2) {
                    String testCode = parts[2].split("```")[0].trim();
                    Path testOut = Paths.get(outputDir, "src/test/java/com/migrated", baseName + "Test.java");
                    Files.createDirectories(testOut.getParent());
                    Files.writeString(testOut, testCode);
                    generatedFiles.add(testOut.toString());
                }
            } else {
                Path fallbackOut = Paths.get(outputDir, "src/main/java/com/migrated/Fallback.java");
                Files.createDirectories(fallbackOut.getParent());
                Files.writeString(fallbackOut, response);
                generatedFiles.add(fallbackOut.toString());
            }

            log.info("Converted file {} successfully. Response length: {}", status.getSourceFile(), response.length());

            status.setStatus(MigrationStatus.NEEDS_REVIEW);
            status.setConverted(true);
            status.setTargetFiles(generatedFiles);
            repository.save(status);
        } catch (Exception e) {
            log.error("Error during conversion for file {}", status.getSourceFile(), e);
            status.setStatus(MigrationStatus.ERROR);
            status.setErrorMessage(e.getMessage());
            repository.save(status);
        }
    }
}
