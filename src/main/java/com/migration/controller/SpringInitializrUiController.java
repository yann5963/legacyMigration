package com.migration.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Contrôleur pour gérer les vues de l'interface utilisateur de Spring Initializr.
 * SpringInitializrUiController
 */
@Controller
public class SpringInitializrUiController {

    /**
     * Affiche la vue de Spring Initializr.
     * @return
     */
    @GetMapping("/spring-initializr")
    public String springInitializrPage() {
        return "spring-initializr";
    }
}
