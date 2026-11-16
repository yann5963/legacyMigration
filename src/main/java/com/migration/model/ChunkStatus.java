package com.migration.model;

/**
 * Statut d'un chunk de code lors du processus de migration.
 * ChunkStatus
 */
public enum ChunkStatus {
    PENDING,
    CONVERTING,
    SUCCESS,
    ERROR
}
