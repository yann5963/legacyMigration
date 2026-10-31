package com.migration.controller;

import com.migration.model.InitializrRequest;
import com.migration.service.SpringInitializrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/initializr")
@RequiredArgsConstructor
public class SpringInitializrApiController {

    private final SpringInitializrService initializrService;

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
