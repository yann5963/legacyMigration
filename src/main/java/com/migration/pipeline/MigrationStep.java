package com.migration.pipeline;

/**
 * Interface commune pour toutes les étapes du pipeline de migration.
 */
public interface MigrationStep {

    /**
     * Exécute l'étape de migration.
     */
    void execute();

    /**
     * Nom de l'étape, utilisé pour les logs et l'orchestration.
     */
    String getStepName();
}
