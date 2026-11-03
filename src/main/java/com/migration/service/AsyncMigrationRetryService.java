package com.migration.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.migration.ai.MigrationAiService;
import com.migration.model.ChunkStatus;
import com.migration.model.FileChunkStatus;
import com.migration.model.MethodChunk;
import com.migration.model.ContextMode;
import com.migration.model.ContextReference;
import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.parser.LegacyCodeParserService;
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
    private final LegacyCodeParserService parserService;

    @Value("${migration.target.output-dir}")
    private String outputDir;

    @Async
    public void convertFile(Long fileId, String customPrompt, List<com.migration.dto.ContextReferenceDto> contextFilesDto) {
        MigrationFileStatus status = repository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid file ID: " + fileId));

        status.setStatus(MigrationStatus.CONVERTING);
        status.setErrorMessage(null);
        if (customPrompt != null && !customPrompt.isEmpty()) {
            status.setLastCustomPrompt(customPrompt);
        }

        if (contextFilesDto != null) {
            status.getContextFiles().clear();
            for (com.migration.dto.ContextReferenceDto dto : contextFilesDto) {
                ContextReference ref = new ContextReference();
                ref.setFilePath(dto.getFilePath());
                ref.setMode(dto.getMode());
                status.getContextFiles().add(ref);
            }
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

            if (parserService.shouldChunk(userMessage)) {
                processLargeFile(status, userMessage, customPrompt);
            } else {
                processStandardFile(status, userMessage, customPrompt);
            }

        } catch (Exception e) {
            log.error("Error during conversion for file {}", status.getSourceFile(), e);
            status.setStatus(MigrationStatus.ERROR);
            status.setErrorMessage(e.getMessage());
            repository.save(status);
        }
    }

    private String resolveContextFiles(List<ContextReference> contextFiles) {
        if (contextFiles == null || contextFiles.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n### Fichiers de référence pertinents\n");
        sb.append("Utilise ces fichiers de référence comme source de vérité pour les types, contrats, annotations et conventions. Ne génère QUE le code du fichier cible demandé.\n\n");
        for (ContextReference ref : contextFiles) {
            try {
                Path path = Paths.get(ref.getFilePath());
                if (Files.exists(path)) {
                    String content = Files.readString(path);
                    if (ref.getMode() == ContextMode.SIGNATURE_ONLY) {
                        content = parserService.extractSignatures(content);
                    }
                    sb.append("Fichier: ").append(ref.getFilePath()).append(" (Mode: ").append(ref.getMode()).append(")\n");
                    sb.append("```java\n").append(content).append("\n```\n\n");
                }
            } catch (Exception e) {
                log.warn("Could not read context file: {}", ref.getFilePath(), e);
            }
        }
        return sb.toString();
    }

    private void processStandardFile(MigrationFileStatus status, String userMessage, String customPrompt) throws Exception {
        String systemPrompt = "Tu es un développeur Java expert. Récris le code legacy J2EE suivant en une classe Spring Boot moderne. Tu DOIS générer DEUX blocs de code distincts entourés par ```java ... ``` : d'abord la classe source, ensuite la classe de test JUnit 5 (avec Mockito).";
        systemPrompt = appendRagContext(systemPrompt, userMessage);

        systemPrompt += resolveContextFiles(status.getContextFiles());

        String additionalInstructions = null;
        if (customPrompt != null && !customPrompt.isEmpty()) {
            additionalInstructions = "Ceci est une correction d'une tentative précédente. Suis STRICTEMENT les instructions suivantes : " + customPrompt;
        }

        String response = aiService.analyzeAndRefactor(systemPrompt, Map.of(), userMessage, additionalInstructions);
        saveFilesAndComplete(status, response);
    }

    private void processLargeFile(MigrationFileStatus status, String userMessage, String customPrompt) throws Exception {
        log.info("Processing large file with chunking strategy: {}", status.getSourceFile());

        // Prepare chunks
        String classStructure = parserService.extractClassStructure(userMessage);
        List<MethodChunk> methodChunks = parserService.extractMethods(userMessage);

        status.getChunks().clear();

        FileChunkStatus structureChunk = FileChunkStatus.builder()
            .chunkId(status.getSourceFile() + "_CLASS_STRUCTURE")
            .methodSignature("CLASS_STRUCTURE")
            .originalCode(classStructure)
            .status(ChunkStatus.PENDING)
            .migrationFileStatus(status)
            .build();
        status.getChunks().add(structureChunk);

        for (MethodChunk chunk : methodChunks) {
            status.getChunks().add(FileChunkStatus.builder()
                .chunkId(status.getSourceFile() + "_" + chunk.getMethodName())
                .methodSignature(chunk.getOriginalSignature())
                .originalCode(chunk.getOriginalCode())
                .status(ChunkStatus.PENDING)
                .migrationFileStatus(status)
                .build());
        }
        repository.save(status);

        // Map: Translate Structure
        structureChunk.setStatus(ChunkStatus.CONVERTING);
        repository.save(status);

        String skeletonPrompt = "Tu es un développeur Java expert. Récris le squelette de classe legacy J2EE suivant en une classe Spring Boot moderne. Ne garde que la structure (annotations, imports, variables globales, signatures de méthodes vides). Ne fournis pas les corps des méthodes et ne fournis pas de tests. Fournis uniquement le code dans un bloc ```java ... ```.";
        skeletonPrompt = appendRagContext(skeletonPrompt, classStructure);

        skeletonPrompt += resolveContextFiles(status.getContextFiles());

        String skeletonResponse = aiService.analyzeAndRefactor(skeletonPrompt, Map.of(), classStructure, customPrompt);
        String targetSkeleton = extractCodeBlock(skeletonResponse);

        structureChunk.setConvertedCode(targetSkeleton);
        structureChunk.setStatus(ChunkStatus.SUCCESS);
        repository.save(status);

        // Map: Translate Methods
        for (FileChunkStatus chunk : status.getChunks()) {
            if (chunk.getMethodSignature().equals("CLASS_STRUCTURE")) continue;

            chunk.setStatus(ChunkStatus.CONVERTING);
            repository.save(status);

            String methodPrompt = "Convertis uniquement cette méthode en utilisant l'API Spring Data, sachant qu'elle appartient à la classe cible dont le squelette est :\n" + targetSkeleton + "\n\nNe renvoie QUE la méthode convertie dans un bloc ```java ... ```. N'inclus pas la classe entière.";
            methodPrompt += resolveContextFiles(status.getContextFiles());

            String methodResponse = aiService.analyzeAndRefactor(methodPrompt, Map.of(), chunk.getOriginalCode(), customPrompt);

            chunk.setConvertedCode(extractCodeBlock(methodResponse));
            chunk.setStatus(ChunkStatus.SUCCESS);
            repository.save(status);
        }

        // Reduce: Reassemble
        CompilationUnit targetCu = StaticJavaParser.parse(targetSkeleton);
        for (FileChunkStatus chunk : status.getChunks()) {
            if (chunk.getMethodSignature().equals("CLASS_STRUCTURE")) continue;

            try {
                MethodDeclaration convertedMethod = StaticJavaParser.parseBodyDeclaration(chunk.getConvertedCode()).asMethodDeclaration();

                targetCu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(convertedMethod.getNameAsString()) && m.getParameters().size() == convertedMethod.getParameters().size())
                    .findFirst()
                    .ifPresent(m -> m.replace(convertedMethod));

            } catch (Exception e) {
                log.error("Failed to parse converted method {} for reassembly.", chunk.getChunkId(), e);
            }
        }

        String finalSourceCode = targetCu.toString();

        // Generate Test for final reassembled code
        String testPrompt = "Génère une classe de test JUnit 5 avec Mockito pour le code Spring Boot suivant. Fournis uniquement le code dans un bloc ```java ... ```.";
        String testResponse = aiService.analyzeAndRefactor(testPrompt, Map.of(), finalSourceCode, customPrompt);
        String finalTestCode = extractCodeBlock(testResponse);

        // Format final response to match processStandardFile expectations
        String combinedResponse = "```java\n" + finalSourceCode + "\n```\n```java\n" + finalTestCode + "\n```";
        saveFilesAndComplete(status, combinedResponse);
    }

    private String appendRagContext(String prompt, String contentToSearch) {
        try {
            List<Document> similarDocs = vectorStore.similaritySearch(SearchRequest.query(contentToSearch).withTopK(2));
            if (!similarDocs.isEmpty()) {
                String context = similarDocs.stream().map(Document::getContent).collect(Collectors.joining("\n---\n"));
                return prompt + "\n\nContexte lié et exemples validés précédemment :\n" + context;
            }
        } catch (Exception ex) {
            log.warn("Impossible de joindre le VectorStore pour le RAG.", ex);
        }
        return prompt;
    }

    private String extractCodeBlock(String response) {
        String[] parts = response.split("```java");
        if (parts.length > 1) {
            return parts[1].split("```")[0].trim();
        }
        return response.trim(); // fallback if no code blocks
    }

    private void saveFilesAndComplete(MigrationFileStatus status, String response) throws Exception {
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
    }
}
