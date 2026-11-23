package com.migration.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Représente un fichier cible généré lors de la conversion multi-fichiers (1 -> N)
 * d'un {@link MigrationFileStatus}.
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 500)
    private String relativeTargetPath;

    @Column(length = 50)
    private String artifactType;

    @Column(columnDefinition = "TEXT")
    private String generatedCode;

    private boolean validated;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "migration_file_status_id")
    @ToString.Exclude
    @JsonIgnore
    private MigrationFileStatus migrationFileStatus;
}
