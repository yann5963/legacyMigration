package com.migration.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpringInitializrUiController {

    @GetMapping("/spring-initializr")
    public String springInitializrPage() {
        return "spring-initializr";
    }
}
