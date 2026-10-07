package com.migration.pipeline;

import com.github.javaparser.ast.CompilationUnit;
import com.migration.ai.MigrationAiService;
import com.migration.ai.RagService;
import com.migration.parser.LegacyCodeParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessLogicMigrationStep implements MigrationStep {

    private final LegacyCodeParserService parserService;
    private final MigrationAiService aiService;
    private final RagService ragService;

    @Override
    public void execute() {
        log.info("--- Démarrage de la migration de la logique métier ---");

        // 1. Lire tous les fichiers Java legacy (Servlets, DAO, etc.)
        List<CompilationUnit> asts = parserService.parseAllLegacyFiles();

        for (CompilationUnit ast : asts) {
            if (ast.getTypes().isEmpty()) {
                log.warn("Fichier vide ou sans type trouvé, ignoré.");
                continue;
            }

            String sourceCode = ast.toString();
            String typeName = ast.getType(0).getNameAsString();

            // 2. Extraire le contexte pertinent (ex: entités JPA générées précédemment)
            String context = ragService.findRelevantContext("JPA Entities for " + typeName, 3);

            // 3. Demander au LLM de refactoriser le code legacy en Service Spring Boot
            String systemPrompt = "Tu es un développeur Java expert. Récris le code legacy J2EE suivant en une classe @Service Spring Boot moderne. Remplace les appels JDBC directs par l'utilisation des Repositories JPA fournis dans le contexte : \n" + context;

            log.info("Refactoring de la classe {} via LLM...", typeName);
            // TODO: Appeler aiService.analyzeAndRefactor(...)
        }

        log.info("--- Fin de la migration de la logique métier ---");
    }

    @Override
    public String getStepName() {
        return "Business Logic Migration (Servlets/JDBC -> @Service)";
    }
}
