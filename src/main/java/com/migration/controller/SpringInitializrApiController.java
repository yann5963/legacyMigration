package com.migration.controller;

import com.migration.model.InitializrRequest;
import com.migration.service.SpringInitializrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur pour gérer les interactions avec l'API Spring Initializr.
 * SpringInitializrApiController
 */
@RestController
@RequestMapping("/api/initializr")
@RequiredArgsConstructor
public class SpringInitializrApiController {

    private final SpringInitializrService initializrService;


    /**
     * Récupère les métadonnées de Spring Initializr.
     * @return
     */
    @GetMapping(value = "/metadata", produces = "application/vnd.initializr.v2.2+json")
    public ResponseEntity<String> getMetadata() {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.initializr.v2.2+json");
        HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
        return restTemplate.exchange("https://start.spring.io/metadata/client", HttpMethod.GET, entity, String.class);
    }

    /**
     * Génère un projet Spring Initializr basé sur la requête fournie.
     * @param request
     * @return
     */
    @PostMapping("/generate")
    public ResponseEntity<String> generateProject(@RequestBody InitializrRequest request) {
        try {
            initializrService.generateProject(request);
            return ResponseEntity.ok("Projet généré avec succès.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Erreur interne lors de la génération: " + e.getMessage());
        }
    }
}
