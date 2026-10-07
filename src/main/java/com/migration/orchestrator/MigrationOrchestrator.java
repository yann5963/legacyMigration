package com.migration.orchestrator;

import com.migration.pipeline.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Point d'entrée principal de l'application qui orchestre l'exécution des étapes de migration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MigrationOrchestrator {

    // Injection des étapes de migration.
    // Spring garantit l'ordre si on utilise @Order sur les classes, mais ici on les appelle explicitement
    // pour garantir l'ordre strict du pipeline décrit.
    private final ProjectInitializerStep initializerStep;
    private final DatabaseMigrationStep databaseStep;
    private final RouteMigrationStep routeStep;
    private final BusinessLogicMigrationStep businessLogicStep;
    private final SecurityMigrationStep securityStep;

    @Async
    public void startMigration() {
        log.info("==========================================================");
        log.info("Démarrage de l'Outil de Migration Automatisé");
        log.info("==========================================================");

        try {
            // Liste ordonnée des étapes à exécuter
            List<MigrationStep> pipeline = List.of(
                    initializerStep,
                    databaseStep,
                    routeStep,
                    businessLogicStep,
                    securityStep
            );

            // Exécution séquentielle du pipeline
            for (int i = 0; i < pipeline.size(); i++) {
                MigrationStep step = pipeline.get(i);
                log.info(">>> Étape {}/{} : {}", i + 1, pipeline.size(), step.getStepName());
                step.execute();
            }

            log.info("==========================================================");
            log.info("Migration terminée avec succès !");
            log.info("Le code Spring Boot généré est disponible dans le répertoire cible.");
            log.info("==========================================================");

        } catch (Exception e) {
            log.error("Une erreur critique est survenue lors de la migration. Arrêt du processus.", e);
        }
    }
}
