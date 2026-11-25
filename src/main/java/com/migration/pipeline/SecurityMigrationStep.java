package com.migration.pipeline;

import com.migration.ai.MigrationAiService;
import com.migration.config.MigrationPromptsProperties;
import com.migration.parser.LegacyCodeParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityMigrationStep implements MigrationStep {

    private final LegacyCodeParserService parserService;
    private final MigrationAiService aiService;
    private final MigrationPromptsProperties prompts;

    @Override
    public void execute() {
        log.info("--- Démarrage de la migration de la sécurité ---");

        // 1. Analyser les filtres J2EE et la gestion de session (souvent dans web.xml ou des classes Filter)
        // On pourrait récupérer cela via le parser ou le RAG
        String securityContextInfo = "Extraction simulée des rôles : ADMIN, USER, MANAGER. Filtrage sur /admin/*";

        // 2. Générer la configuration Spring Security
        String systemPrompt = prompts.render(prompts.getPipelineDirectives().getOrDefault("security", ""),
                java.util.Map.of("rules", securityContextInfo));

        log.info("Génération de la configuration Spring Security...");
        aiService.analyzeAndRefactor(systemPrompt, java.util.Map.of("rules", securityContextInfo),
                securityContextInfo);

        log.info("--- Fin de la migration de la sécurité ---");
    }

    @Override
    public String getStepName() {
        return "Security Migration (Filters -> Spring Security)";
    }
}
