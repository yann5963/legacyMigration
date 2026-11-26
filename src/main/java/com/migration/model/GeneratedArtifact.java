package com.migration.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Index;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Représente un fichier cible généré lors de la conversion multi-fichiers (1 -> N)
 * d'un {@link MigrationFileStatus}.
 */
@Entity
@Table(name = "generated_artifact", indexes = @Index(name = "idx_generated_artifact_fqcn", columnList = "fqcn"))
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

    @Column(length = 500)
    private String fqcn;

    @Column(length = 200)
    private String sqlTableName;

    @Column(columnDefinition = "TEXT")
    private String compactSignature;

    @Column(columnDefinition = "TEXT")
    private String semanticDescription;

    /** Un artefact verrouillé n'est jamais modifié par un upsert du registre. */
    private Boolean locked;

    private LocalDateTime updatedAt;

    @Column(name = "migration_file_status_id", insertable = false, updatable = false)
    private Long sourceStatusId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "migration_file_status_id")
    @ToString.Exclude
    @JsonIgnore
    private MigrationFileStatus migrationFileStatus;
}
