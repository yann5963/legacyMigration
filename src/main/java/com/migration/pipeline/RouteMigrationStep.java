package com.migration.pipeline;

import com.migration.ai.MigrationAiService;
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

    @Override
    public void execute() {
        log.info("--- Démarrage de la migration des routes ---");

        // 1. Parser le fichier web.xml pour extraire les servlet-mappings
        String webXmlContent = parserService.readRawFile("WEB-INF/web.xml");

        if (webXmlContent != null) {
            // 2. Demander au LLM de convertir le web.xml en squelettes de @RestController
            String systemPrompt = "Tu es un expert Spring Boot. Convertis les mappings de servlets de ce web.xml J2EE en classes @RestController Spring Boot avec des annotations @RequestMapping ou @GetMapping/@PostMapping.";

            log.info("Demande au LLM de générer les contrôleurs REST...");
            // TODO: Appeler aiService.analyzeAndRefactor(...) avec webXmlContent

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
