package com.migration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migration.ai.MigrationAiService;
import com.migration.dto.ReverseCheckReport;
import com.migration.model.GeneratedArtifact;
import com.migration.model.MigrationFileStatus;
import com.migration.model.ParityStatus;
import com.migration.repository.MigrationFileStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Service orchestrant l'audit de parité fonctionnelle (Reverse Checking)
 * entre un fichier source legacy et ses artefacts Spring Boot générés.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReverseCheckingService {

    private final MigrationFileStatusRepository repository;
    private final MigrationAiService migrationAiService;
    private final ObjectMapper objectMapper;

    /**
     * Exécute un audit comparatif complet pour un fichier de migration donné
     * et persiste le score, le statut et le rapport JSON en base de données.
     *
     * @param fileId   Identifiant du {@link MigrationFileStatus}.
     * @param llmModel Modèle LLM optionnel à utiliser pour l'audit.
     * @return Le rapport structuré {@link ReverseCheckReport}.
     */
    @Transactional
    public ReverseCheckReport performReverseCheck(Long fileId, String llmModel) {
        MigrationFileStatus status = repository.findById(fileId)
                .orElseThrow(() -> new NoSuchElementException("Fichier de migration introuvable : " + fileId));

        String sourceCode = readSourceCode(status);
        String targetArtifactsText = collectTargetArtifacts(status);

        ReverseCheckReport report = migrationAiService.reverseCheck(
                status.getSourceFile(),
                sourceCode,
                targetArtifactsText,
                llmModel);

        status.setParityScore(report.score());
        status.setParityStatus("CONFORME".equalsIgnoreCase(report.globalStatus())
                ? ParityStatus.CONFORME
                : ParityStatus.ECARTS_DETECTES);
        status.setParityCheckedAt(LocalDateTime.now());
        status.setParityStale(false);
        try {
            status.setParityReport(objectMapper.writeValueAsString(report));
        } catch (JsonProcessingException e) {
            log.warn("Impossible de sérialiser le rapport ReverseCheck en JSON pour {} : {}",
                    status.getSourceFile(), e.getMessage());
        }
        repository.save(status);

        return report;
    }

    /**
     * Récupère le dernier rapport de Reverse Checking sauvegardé en base pour un fichier donné,
     * sans déclencher d'appel IA.
     */
    @Transactional(readOnly = true)
    public Optional<SavedReverseCheck> getSavedReverseCheck(Long fileId) {
        MigrationFileStatus status = repository.findById(fileId)
                .orElseThrow(() -> new NoSuchElementException("Fichier de migration introuvable : " + fileId));

        if (status.getParityReport() == null || status.getParityReport().isBlank()) {
            return Optional.empty();
        }
        try {
            ReverseCheckReport report = objectMapper.readValue(status.getParityReport(), ReverseCheckReport.class);
            return Optional.of(new SavedReverseCheck(
                    report,
                    status.getParityScore(),
                    status.getParityStatus(),
                    status.getParityCheckedAt(),
                    status.isParityStale()));
        } catch (JsonProcessingException e) {
            log.warn("Rapport ReverseCheck JSON illisible pour {} : {}", status.getSourceFile(), e.getMessage());
            return Optional.empty();
        }
    }

    public record SavedReverseCheck(
            ReverseCheckReport report,
            Integer parityScore,
            ParityStatus parityStatus,
            LocalDateTime parityCheckedAt,
            boolean parityStale
    ) {
    }

    private String readSourceCode(MigrationFileStatus status) {
        if (status.getAbsolutePath() == null || status.getAbsolutePath().isBlank()) {
            throw new IllegalStateException("Chemin du fichier source legacy manquant pour l'audit.");
        }
        Path originalPath = Paths.get(status.getAbsolutePath());
        if (!Files.exists(originalPath)) {
            throw new IllegalStateException("Fichier source legacy introuvable sur le disque : " + status.getAbsolutePath());
        }
        return readFileSafely(originalPath);
    }

    private String collectTargetArtifacts(MigrationFileStatus status) {
        StringBuilder sb = new StringBuilder();
        List<GeneratedArtifact> artifacts = status.getGeneratedArtifacts();
        if (artifacts != null && !artifacts.isEmpty()) {
            for (GeneratedArtifact artifact : artifacts) {
                if (artifact.getGeneratedCode() == null || artifact.getGeneratedCode().isBlank()) {
                    continue;
                }
                sb.append("#### Artefact [").append(artifact.getArtifactType() != null ? artifact.getArtifactType() : "JAVA")
                        .append("] : ").append(artifact.getRelativeTargetPath() != null ? artifact.getRelativeTargetPath() : "inconnu")
                        .append("\n```java\n")
                        .append(artifact.getGeneratedCode())
                        .append("\n```\n\n");
            }
        } else if (status.getTargetFiles() != null && !status.getTargetFiles().isEmpty()) {
            for (String targetPathStr : status.getTargetFiles()) {
                Path targetPath = Paths.get(targetPathStr);
                if (Files.exists(targetPath)) {
                    sb.append("#### Fichier cible : ").append(targetPathStr)
                            .append("\n```java\n")
                            .append(readFileSafely(targetPath))
                            .append("\n```\n\n");
                }
            }
        }

        if (sb.isEmpty()) {
            throw new IllegalStateException("Aucun artefact Spring Boot généré disponible pour l'audit.");
        }
        return sb.toString();
    }

    private String readFileSafely(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            CharsetDecoder utf8Decoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString().trim();
            } catch (CharacterCodingException e) {
                return new String(bytes, Charset.forName("Windows-1252")).trim();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Erreur de lecture du fichier " + path + " : " + e.getMessage(), e);
        }
    }
}
