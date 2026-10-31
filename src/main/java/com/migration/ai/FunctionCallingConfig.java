package com.migration.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.Function;

/**
 * Configuration pour les appels de fonctions (Function Calling) par le LLM.
 */
@Configuration
@Slf4j
public class FunctionCallingConfig {

    public record WriteFileRequest(String relativePath, String content) {}
    public record WriteFileResponse(boolean success, String message) {}

    /**
     * Permet au LLM de demander l'écriture d'un fichier généré sur le disque.
     */
    @Bean
    @Description("Write generated source code to the destination directory")
    public Function<WriteFileRequest, WriteFileResponse> writeGeneratedCodeFunction() {
        return request -> {
            log.info("Le LLM demande l'écriture d'un fichier : {}", request.relativePath());
            // TODO: Implémenter la logique réelle d'écriture sur le disque dans le répertoire cible
            // (utiliser la propriété migration.target.output-dir)
            try {
                // Simuler l'écriture sur le disque
                Files.write(Paths.get(request.relativePath()), request.content().getBytes());
            } catch (Exception e) {
                log.error("Erreur lors de l'écriture du fichier : {}", request.relativePath(), e);
                return new WriteFileResponse(false, "Erreur lors de l'écriture du fichier : " + e.getMessage());
            }

            // Pour l'instant, on simule le succès
            return new WriteFileResponse(true, "Fichier " + request.relativePath() + " écrit avec succès.");
        };
    }
}
