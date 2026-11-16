package com.migration.model;

import lombok.Data;
import java.util.List;

/**
 * Représente une requête pour générer un projet Spring Boot via Spring Initializr.
 * InitializrRequest
 */
@Data
public class InitializrRequest {
    private List<String> dependencies;
}
