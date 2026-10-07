package com.migration.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardUiController {

    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard"; // Renders dashboard.html
    }

    @GetMapping("/configuration")
    public String configuration() {
        return "configuration"; // Renders configuration.html
    }
}
