package com.migration.service;

import com.migration.ai.MigrationAiService;
import com.migration.dto.FilePreAnalysisResult;
import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.parser.LegacyCodeParserService;
import com.migration.repository.MigrationFileStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Service asynchrone de pré-analyse sémantique des fichiers scannés.
 * Enrichit chaque {@link MigrationFileStatus} avec ses métadonnées architecturales,
 * son résumé fonctionnel et son estimation de complexité de migration.
 *
 * Les fichiers Java triviaux (DTO purs, classes de constantes, getters/setters uniquement)
 * sont détectés de manière déterministe via JavaParser sans appel au LLM.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticPreAnalysisService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 250;

    private final MigrationFileStatusRepository repository;
    private final LegacyCodeParserService parserService;
    private final MigrationAiService aiService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger trivialCount = new AtomicInteger(0);
    private final AtomicReference<String> currentFile = new AtomicReference<>(null);

    /**
     * Retourne l'état d'avancement en temps réel de la pré-analyse sémantique.
     */
    public Map<String, Object> getProgress() {
        return Map.of(
                "running", running.get(),
                "currentFile", currentFile.get() != null ? currentFile.get() : "",
                "processed", processedCount.get(),
                "total", totalCount.get(),
                "failed", failedCount.get(),
                "trivial", trivialCount.get()
        );
    }

    /**
     * Lance de manière asynchrone la pré-analyse de tous les fichiers en statut SCANNED ou ANALYSIS_FAILED.
     * Une erreur sur un fichier n'interrompt jamais le traitement des fichiers suivants.
     */
    @Async
    public void analyzeAllScannedFiles(String llmModel) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Une pré-analyse sémantique est déjà en cours, requête ignorée.");
            return;
        }

        try {
            List<MigrationFileStatus> filesToAnalyze = repository.findByStatusIn(
                    List.of(MigrationStatus.SCANNED, MigrationStatus.ANALYSIS_FAILED)).stream()
                    .sorted(Comparator.comparing(MigrationFileStatus::isEntryPoint).reversed()
                            .thenComparing(MigrationFileStatus::getLayer,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();

            totalCount.set(filesToAnalyze.size());
            processedCount.set(0);
            failedCount.set(0);
            trivialCount.set(0);
            currentFile.set(null);

            log.info("Démarrage de la pré-analyse sémantique asynchrone pour {} fichier(s).", filesToAnalyze.size());

            int analyzed = 0;
            for (MigrationFileStatus file : filesToAnalyze) {
                currentFile.set(file.getSourceFile());
                boolean wasTrivial = analyzeSingleFileInternal(file, llmModel);
                processedCount.incrementAndGet();
                if (file.getStatus() == MigrationStatus.ANALYZED) {
                    analyzed++;
                    if (wasTrivial) {
                        trivialCount.incrementAndGet();
                    }
                } else if (file.getStatus() == MigrationStatus.ANALYSIS_FAILED) {
                    failedCount.incrementAndGet();
                }
            }

            log.info("Pré-analyse sémantique terminée. Analysés: {} (dont {} triviaux sans LLM), Échecs: {}.",
                    analyzed, trivialCount.get(), failedCount.get());
        } finally {
            currentFile.set(null);
            running.set(false);
        }
    }

    /**
     * Lance de manière asynchrone la pré-analyse d'un fichier spécifique.
     */
    @Async
    public void analyzeFile(Long fileId, String llmModel) {
        repository.findById(fileId).ifPresentOrElse(
                file -> {
                    boolean standalone = running.compareAndSet(false, true);
                    try {
                        if (standalone) {
                            totalCount.set(1);
                            processedCount.set(0);
                            failedCount.set(0);
                            trivialCount.set(0);
                        }
                        currentFile.set(file.getSourceFile());
                        boolean wasTrivial = analyzeSingleFileInternal(file, llmModel);
                        if (standalone) {
                            processedCount.set(1);
                            if (wasTrivial) trivialCount.set(1);
                            if (file.getStatus() == MigrationStatus.ANALYSIS_FAILED) failedCount.set(1);
                        }
                    } finally {
                        if (standalone) {
                            currentFile.set(null);
                            running.set(false);
                        }
                    }
                },
                () -> log.warn("Impossible de pré-analyser le fichier id={} : introuvable en base.", fileId)
        );
    }

    /**
     * Exécute la pré-analyse d'un fichier unique avec isolation stricte des erreurs.
     *
     * @return true si le fichier a été classé TRIVIAL sans appel LLM.
     */
    boolean analyzeSingleFileInternal(MigrationFileStatus fileStatus, String llmModel) {
        try {
            if (!fileStatus.isReachable()) {
                fileStatus.setArchitecturalRole("DEAD_CODE_CANDIDATE");
                fileStatus.setMigrationComplexity("TRIVIAL");
                fileStatus.setTargetSpringArtifact("ToDelete");
                fileStatus.setFunctionalSummary(
                        "Fichier non atteint depuis les points d'entrée identifiés (code mort potentiel).");
                fileStatus.setStatus(MigrationStatus.ANALYZED);
                fileStatus.setErrorMessage(null);
                repository.save(fileStatus);
                return true;
            }
            fileStatus.setStatus(MigrationStatus.ANALYZING);
            fileStatus.setErrorMessage(null);
            repository.save(fileStatus);

            String content = readFileContent(fileStatus.getAbsolutePath());

            // Optimisation : détection déterministe via JavaParser pour éviter les appels LLM sur les fichiers triviaux
            if ("JAVA".equalsIgnoreCase(fileStatus.getFileType()) && parserService.isTrivialJavaCode(content)) {
                log.debug("Fichier trivial détecté via JavaParser (sans appel LLM) : {}", fileStatus.getSourceFile());
                applyTrivialMetadata(fileStatus, content);
                repository.save(fileStatus);
                return true;
            }

            String roots = fileStatus.getRootEntryPoints();
            FilePreAnalysisResult result = roots == null || roots.isBlank()
                    ? aiService.preAnalyzeFile(fileStatus.getSourceFile(), fileStatus.getFileType(), content, llmModel)
                    : aiService.preAnalyzeFile(fileStatus.getSourceFile(), fileStatus.getFileType(), content, llmModel, roots);

            applyPreAnalysisResult(fileStatus, result);
            fileStatus.setStatus(MigrationStatus.ANALYZED);
            repository.save(fileStatus);
            return false;

        } catch (Exception e) {
            log.warn("Échec de la pré-analyse sémantique pour le fichier {} : {}",
                    fileStatus.getSourceFile(), e.getMessage(), e);
            fileStatus.setStatus(MigrationStatus.ANALYSIS_FAILED);
            fileStatus.setErrorMessage(truncate(e.getMessage(), MAX_ERROR_MESSAGE_LENGTH));
            repository.save(fileStatus);
            return false;
        }
    }

    private void applyTrivialMetadata(MigrationFileStatus fileStatus, String content) {
        String pkg = parserService.extractPackageName(content);
        String lot = deriveLotFromPackage(pkg);

        fileStatus.setArchitecturalRole("TRIVIAL");
        fileStatus.setMigrationComplexity("TRIVIAL");
        fileStatus.setFunctionalSummary(
                "Fichier Java trivial détecté statiquement (DTO/POJO pur, constantes ou accesseurs uniquement). "
                        + "Aucune logique métier complexe détectée ; migration directe vers un modèle ou record Java moderne.");
        fileStatus.setTargetSpringArtifact("Entity/DTO");
        fileStatus.setDetectedRisks("Aucun risque legacy détecté (classe triviale).");
        fileStatus.setSuggestedFunctionalLot(lot);
        fileStatus.setStatus(MigrationStatus.ANALYZED);
    }

    private void applyPreAnalysisResult(MigrationFileStatus fileStatus, FilePreAnalysisResult result) {
        if (result == null) {
            throw new IllegalStateException("Le LLM a retourné un résultat de pré-analyse null.");
        }
        fileStatus.setArchitecturalRole(truncate(defaultIfBlank(result.architecturalRole(), "UNKNOWN"), 100));
        fileStatus.setMigrationComplexity(truncate(defaultIfBlank(result.migrationComplexity(), "MEDIUM"), 50));
        fileStatus.setFunctionalSummary(defaultIfBlank(result.functionalSummary(), "Résumé non fourni."));
        fileStatus.setTargetSpringArtifact(truncate(defaultIfBlank(result.targetSpringArtifact(), "Service"), 100));

        String risksText = (result.detectedRisks() == null || result.detectedRisks().isEmpty())
                ? "Aucun risque spécifique identifié."
                : result.detectedRisks().stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining("\n- ", "- ", ""));
        fileStatus.setDetectedRisks(risksText);
        fileStatus.setSuggestedFunctionalLot(truncate(defaultIfBlank(result.suggestedFunctionalLot(), "Transverse"), 150));
    }

    private String readFileContent(String absolutePath) throws IOException {
        if (absolutePath == null || absolutePath.isBlank()) {
            throw new IllegalArgumentException("Chemin de fichier manquant.");
        }
        Path path = Paths.get(absolutePath);
        if (!Files.exists(path)) {
            throw new IOException("Fichier introuvable sur le disque : " + absolutePath);
        }
        byte[] bytes = Files.readAllBytes(path);
        CharsetDecoder utf8Decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("Windows-1252"));
        }
    }

    private String deriveLotFromPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return "Modèle / Commun";
        }
        String[] parts = packageName.split("\\.");
        return parts[parts.length - 1];
    }

    private String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
