package com.migration.pipeline;

import com.migration.ai.MigrationAiService;
import com.migration.ai.RagService;
import com.migration.config.MigrationPromptsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseMigrationStep implements MigrationStep {

    private final MigrationAiService aiService;
    private final RagService ragService;
    private final MigrationPromptsProperties prompts;

    @Override
    public void execute() {
        log.info("--- Démarrage de la migration de la base de données ---");

        // 1. (Simulé) Connexion à Informix via JDBC pour extraire le schéma (DDL)
        String extractedDdl = extractDdlFromInformix();

        // 2. Indexation du DDL dans le Vector Store pour les étapes suivantes
        ragService.indexContext(extractedDdl, Map.of("type", "schema-ddl", "source", "informix"));

        // 3. Demander au LLM de générer les entités JPA et Repositories
        String systemPrompt = prompts.getPipelineDirectives().getOrDefault("database", "");

        log.info("Demande au LLM de générer les entités JPA...");
        String result = aiService.analyzeAndRefactor(systemPrompt, Map.of(), extractedDdl);
        log.debug("Réponse de génération JPA reçue ({} caractères).", result == null ? 0 : result.length());

        // 4. Utiliser Function Calling pour écrire les fichiers (Géré via aiService / FunctionCallingConfig)

        log.info("--- Fin de la migration de la base de données ---");
    }

    @Override
    public String getStepName() {
        return "Database Migration (DDL -> JPA Entities)";
    }

    private String extractDdlFromInformix() {
        // Logique d'extraction JDBC (non implémentée ici)
        return "CREATE TABLE users (id INT PRIMARY KEY, username VARCHAR(50), role VARCHAR(20));";
    }
}
