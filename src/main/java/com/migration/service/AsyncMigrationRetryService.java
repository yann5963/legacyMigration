package com.migration.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.migration.ai.MigrationAiService;
import com.migration.config.MigrationPromptsProperties;
import com.migration.dto.MultiFileConversionResponse;
import com.migration.model.ChunkStatus;
import com.migration.model.FileChunkStatus;
import com.migration.model.GeneratedArtifact;
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

import java.nio.charset.StandardCharsets;
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
    private final DynamicPromptBuilderService dynamicPromptBuilderService;
    private final MigrationPromptsProperties prompts;
    private final TargetArtifactRegistryService artifactRegistry;

    @Value("${migration.target.output-dir}")
    private String outputDir;

    @Async
    public void convertFile(Long fileId, String customPrompt, List<com.migration.dto.ContextReferenceDto> contextFilesDto, String llmModel) {
        MigrationFileStatus status = repository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid file ID: " + fileId));

        status.setStatus(MigrationStatus.CONVERTING);
        status.setErrorMessage(null);

        if (contextFilesDto != null) {
            status.getContextFiles().clear();
            for (com.migration.dto.ContextReferenceDto dto : contextFilesDto) {
                ContextReference ref = new ContextReference();
                ref.setFilePath(dto.getFilePath());
                ref.setMode(dto.getMode());
                status.getContextFiles().add(ref);
            }
        }

        String effectivePrompt = (customPrompt != null && !customPrompt.trim().isEmpty())
                ? customPrompt
                : dynamicPromptBuilderService.buildConversionPrompt(status, List.of());
        status.setLastCustomPrompt(effectivePrompt);

        repository.save(status);

        try {
            Path filePath = Paths.get(status.getAbsolutePath());
            String userMessage;
            if (Files.exists(filePath)) {
                userMessage = Files.readString(filePath, StandardCharsets.ISO_8859_1);
            } else {
                log.warn("File not found on disk, using dummy content. Path: {}", status.getAbsolutePath());
                userMessage = "Dummy content for " + status.getSourceFile();
            }

            if (parserService.shouldChunk(userMessage)) {
                processLargeFile(status, userMessage, effectivePrompt, llmModel);
            } else {
                processStandardFile(status, userMessage, effectivePrompt, llmModel);
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
        StringBuilder sb = new StringBuilder(prompts.getContextHeader()).append("\n\n");
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

    private void processStandardFile(MigrationFileStatus status, String userMessage, String effectivePrompt, String llmModel) throws Exception {
        String systemPrompt = effectivePrompt;
        systemPrompt = appendRagContext(systemPrompt, userMessage);
        systemPrompt += resolveContextFiles(status.getContextFiles());

        MultiFileConversionResponse response = aiService.convertToMultiFile(systemPrompt, userMessage, llmModel);
        if (response != null && response.artifacts() != null && !response.artifacts().isEmpty()) {
            saveMultiFileArtifacts(status, response.artifacts());
        } else {
            throw new IllegalStateException("Le LLM n'a retourné aucun artefact pour " + status.getSourceFile());
        }
    }

    private void processLargeFile(MigrationFileStatus status, String userMessage, String customPrompt, String llmModel) throws Exception {
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

        String skeletonPrompt = prompts.getConversion().getSkeleton();
        skeletonPrompt = appendRagContext(skeletonPrompt, classStructure);

        skeletonPrompt += resolveContextFiles(status.getContextFiles());

        String skeletonResponse = aiService.analyzeAndRefactor(skeletonPrompt, Map.of(), classStructure, customPrompt, llmModel);
        String targetSkeleton = extractCodeBlock(skeletonResponse);

        structureChunk.setConvertedCode(targetSkeleton);
        structureChunk.setStatus(ChunkStatus.SUCCESS);
        repository.save(status);

        // Map: Translate Methods
        for (FileChunkStatus chunk : status.getChunks()) {
            if (chunk.getMethodSignature().equals("CLASS_STRUCTURE")) continue;

            chunk.setStatus(ChunkStatus.CONVERTING);
            repository.save(status);

            String methodPrompt = prompts.render(prompts.getConversion().getMethod(),
                    Map.of("targetSkeleton", targetSkeleton));
            methodPrompt += resolveContextFiles(status.getContextFiles());

            String methodResponse = aiService.analyzeAndRefactor(methodPrompt, Map.of(), chunk.getOriginalCode(), customPrompt, llmModel);

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
        String testPrompt = prompts.getConversion().getTest();
        String testResponse = aiService.analyzeAndRefactor(testPrompt, Map.of(), finalSourceCode, customPrompt, llmModel);
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
                return prompt + prompts.getRagHeader() + context;
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

    private void saveMultiFileArtifacts(MigrationFileStatus status,
                                        List<MultiFileConversionResponse.TargetFileItem> artifacts) throws Exception {
        List<String> generatedFiles = new ArrayList<>();
        status.getGeneratedArtifacts().clear();

        Path baseOutputPath = Paths.get(outputDir).toAbsolutePath().normalize();
        for (MultiFileConversionResponse.TargetFileItem item : artifacts) {
            if (item == null || item.code() == null || item.code().isBlank()) {
                continue;
            }
            String relPath = (item.relativeTargetPath() != null && !item.relativeTargetPath().isBlank())
                    ? item.relativeTargetPath().trim().replace("\\", "/")
                    : "src/main/java/com/migration/target/" + status.getSourceFile();
            while (relPath.startsWith("/")) {
                relPath = relPath.substring(1);
            }

            Path targetOut = baseOutputPath.resolve(relPath).normalize();
            if (!targetOut.startsWith(baseOutputPath)) {
                throw new SecurityException("Chemin cible hors du répertoire de sortie : " + relPath);
            }
            Files.createDirectories(targetOut.getParent());
            Files.writeString(targetOut, item.code());
            generatedFiles.add(targetOut.toString());

            String artifactType = (item.artifactType() != null && !item.artifactType().isBlank())
                    ? item.artifactType().trim().toUpperCase()
                    : "OTHER";

            GeneratedArtifact artifact = GeneratedArtifact.builder()
                    .relativeTargetPath(relPath)
                    .artifactType(artifactType)
                    .generatedCode(item.code())
                    .validated(false)
                    .migrationFileStatus(status)
                    .build();
            status.getGeneratedArtifacts().add(artifact);
        }

        log.info("Converted file {} into {} artifact(s).", status.getSourceFile(), status.getGeneratedArtifacts().size());

        status.setStatus(MigrationStatus.NEEDS_REVIEW);
        status.setConverted(true);
        status.setTargetFiles(generatedFiles);
        registerInRegistry(repository.save(status), status);
    }

    private void saveFilesAndComplete(MigrationFileStatus status, String response) throws Exception {
        List<String> generatedFiles = new ArrayList<>();
        status.getGeneratedArtifacts().clear();
        String[] parts = response.split("```java");
        if (parts.length > 1) {
            String sourceCode = parts[1].split("```")[0].trim();
            String baseName = status.getSourceFile().replace(".java", "");
            String relSource = "src/main/java/com/migrated/" + baseName + ".java";
            Path sourceOut = Paths.get(outputDir, relSource);
            Files.createDirectories(sourceOut.getParent());
            Files.writeString(sourceOut, sourceCode);
            generatedFiles.add(sourceOut.toString());
            status.getGeneratedArtifacts().add(GeneratedArtifact.builder()
                    .relativeTargetPath(relSource)
                    .artifactType("SOURCE")
                    .generatedCode(sourceCode)
                    .validated(false)
                    .migrationFileStatus(status)
                    .build());
            if (parts.length > 2) {
                String testCode = parts[2].split("```")[0].trim();
                String relTest = "src/test/java/com/migrated/" + baseName + "Test.java";
                Path testOut = Paths.get(outputDir, relTest);
                Files.createDirectories(testOut.getParent());
                Files.writeString(testOut, testCode);
                generatedFiles.add(testOut.toString());
                status.getGeneratedArtifacts().add(GeneratedArtifact.builder()
                        .relativeTargetPath(relTest)
                        .artifactType("TEST")
                        .generatedCode(testCode)
                        .validated(false)
                        .migrationFileStatus(status)
                        .build());
            }
        } else {
            String relFallback = "src/main/java/com/migrated/Fallback.java";
            Path fallbackOut = Paths.get(outputDir, relFallback);
            Files.createDirectories(fallbackOut.getParent());
            Files.writeString(fallbackOut, response);
            generatedFiles.add(fallbackOut.toString());
            status.getGeneratedArtifacts().add(GeneratedArtifact.builder()
                    .relativeTargetPath(relFallback)
                    .artifactType("OTHER")
                    .generatedCode(response)
                    .validated(false)
                    .migrationFileStatus(status)
                    .build());
        }

        log.info("Converted file {} successfully. Response length: {}", status.getSourceFile(), response.length());

        status.setStatus(MigrationStatus.NEEDS_REVIEW);
        status.setConverted(true);
        status.setTargetFiles(generatedFiles);
        registerInRegistry(repository.save(status), status);
    }

    private void registerInRegistry(MigrationFileStatus saved, MigrationFileStatus fallback) {
        if (artifactRegistry == null) {
            return;
        }
        try {
            artifactRegistry.registerOrUpdateArtifacts(saved != null ? saved : fallback);
        } catch (Exception ex) {
            log.warn("Mise à jour du registre d'artefacts cibles impossible pour {}", fallback.getSourceFile(), ex);
        }
    }
}