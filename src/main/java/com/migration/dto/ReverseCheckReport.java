package com.migration.dto;

import java.util.List;

/**
 * Rapport structuré d'audit de parité fonctionnelle (Reverse Checking)
 * entre un fichier source legacy et les artefacts Spring Boot générés.
 */
public record ReverseCheckReport(
        int score,
        String globalStatus,
        String summary,
        List<RuleCheckItem> rules,
        List<String> sqlAndDataChecks
) {
    /**
     * Détail de vérification d'une règle métier ou comportementale.
     */
    public record RuleCheckItem(
            String ruleDescription,
            String status,
            String targetLocation,
            String discrepancyDetails
    ) {
    }
}
