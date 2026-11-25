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
public class RouteMigrationStep implements MigrationStep {

    private final LegacyCodeParserService parserService;
    private final MigrationAiService aiService;
    private final MigrationPromptsProperties prompts;

    @Override
    public void execute() {
        log.info("--- Démarrage de la migration des routes ---");

        // 1. Parser le fichier web.xml pour extraire les servlet-mappings
        String webXmlContent = parserService.readRawFile("WEB-INF/web.xml");

        if (webXmlContent != null) {
            // 2. Demander au LLM de convertir le web.xml en squelettes de @RestController
            String systemPrompt = prompts.getPipelineDirectives().getOrDefault("route", "");

            log.info("Demande au LLM de générer les contrôleurs REST...");
            aiService.analyzeAndRefactor(systemPrompt, java.util.Map.of(), webXmlContent);

            // Si des ambiguïtés (ex: plusieurs servlets pour la même route avec des conditions bizarres),
            // solliciter la validation humaine si configurée.
        } else {
            log.warn("Fichier web.xml introuvable, tentative d'analyse des annotations @WebServlet (si Java EE 6+).");
        }

        log.info("--- Fin de la migration des routes ---");
    }

    @Override
    public String getStepName() {
        return "Routes Migration (web.xml -> @RestController)";
    }
}
