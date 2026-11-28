package com.migration.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Column;
import java.time.LocalDateTime;

/**
 * Représente la configuration d'un projet cible pour la migration.
 * ProjectConfiguration
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectConfiguration {

    @Id
    private Long id;

    private String targetJavaVersion;
    private String targetSpringBootVersion;
    private String targetGroupId;
    private String targetArtifactId;
    private String targetProjectPath;
    private String targetBaseDir;
    private String targetBasePackage;

    private String defaultLlmModel;

    private Boolean lastBuildSuccess;
    private LocalDateTime lastBuildAt;
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int lastBuildErrors;

    @Column(columnDefinition = "TEXT")
    private String lastBuildDiagnostic;
}
