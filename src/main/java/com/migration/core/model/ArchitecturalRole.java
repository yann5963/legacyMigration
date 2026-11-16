package com.migration.core.model;

/**
 * Rôle architectural d'une classe dans le projet.
 * ArchitecturalRole
 */
public enum ArchitecturalRole {
    CONTROLLER("controller", "Contrôleur REST ou MVC"),
    SERVICE("service", "Couche métier et services applicatifs"),
    REPOSITORY("repository", "Accès aux données et DAOs Spring Data"),
    DTO("dto", "Data Transfer Objects, requêtes et réponses API"),
    MODEL_BEAN("model", "Entités, JavaBeans et POJOs métier"),
    CONFIG("config", "Classes de configuration Spring"),
    UTIL("util", "Classes utilitaires et helpers"),
    OTHER("shared", "Composants transverses non catégorisés");

    private final String defaultSubPackage;
    private final String displayName;

    ArchitecturalRole(String defaultSubPackage, String displayName) {
        this.defaultSubPackage = defaultSubPackage;
        this.displayName = displayName;
    }

    public String getDefaultSubPackage() {
        return defaultSubPackage;
    }

    public String getDisplayName() {
        return displayName;
    }
}
