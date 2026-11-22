package com.migration.model;

/**
 * Représente les différents statuts possibles d'une migration.
 * MigrationStatus
 */
public enum MigrationStatus {
    SCANNED,
    ANALYZING,
    ANALYZED,
    ANALYSIS_FAILED,
    CONVERTING,
    NEEDS_REVIEW,
    VALIDATED,
    ERROR
}
