package com.migration.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Contrôleur pour gérer les vues de l'interface utilisateur du tableau de bord.
 * DashboardUiController
 */
@Controller
public class DashboardUiController {

    /**
     * Affiche la vue du tableau de bord.
     * @return
     */
    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard"; // Renders dashboard.html
    }

    /**
     * Affiche la vue de la configuration du projet.
     * @return
     */
    @GetMapping("/configuration")
    public String configuration() {
        return "configuration"; // Renders configuration.html
    }
}
