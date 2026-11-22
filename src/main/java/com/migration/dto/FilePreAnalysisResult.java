package com.migration.dto;

import java.util.List;

/**
 * DTO de sortie structurée retourné par Spring AI lors de la pré-analyse sémantique d'un fichier legacy.
 */
public record FilePreAnalysisResult(
        String architecturalRole,
        String migrationComplexity,
        String functionalSummary,
        String targetSpringArtifact,
        List<String> detectedRisks,
        String suggestedFunctionalLot
) {}
