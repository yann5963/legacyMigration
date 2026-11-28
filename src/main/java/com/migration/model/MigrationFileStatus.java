package com.migration.model;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

/**
 * Représente le statut d'un fichier lors du processus de migration.
 * MigrationFileStatus
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationFileStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String absolutePath;
    private String fileType;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean entryPoint;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean reachable = true;

    @Column(columnDefinition = "TEXT")
    private String rootEntryPoints;

    // Propagated types preserve batch/web intent even for roots with arbitrary class names.
    @Column(columnDefinition = "TEXT")
    private String rootEntryPointTypes;

    private boolean isConverted;
    private boolean isValidated;

    private Integer layer;

    @Column(columnDefinition = "TEXT")
    private String lastCustomPrompt;

    private String sourceFile;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> targetFiles;

    @Enumerated(EnumType.STRING)
    private MigrationStatus status;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(length = 100)
    private String architecturalRole;

    @Column(length = 50)
    private String migrationComplexity;

    @Column(columnDefinition = "TEXT")
    private String functionalSummary;

    @Column(length = 100)
    private String targetSpringArtifact;

    @Column(columnDefinition = "TEXT")
    private String detectedRisks;

    @Column(length = 150)
    private String suggestedFunctionalLot;

    private Integer parityScore;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ParityStatus parityStatus = ParityStatus.NON_VERIFIE;

    @Column(columnDefinition = "TEXT")
    private String parityReport;

    private LocalDateTime parityCheckedAt;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean parityStale = false;

    @OneToMany(mappedBy = "migrationFileStatus", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<FileChunkStatus> chunks = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<ContextReference> contextFiles = new ArrayList<>();

    @OneToMany(mappedBy = "migrationFileStatus", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private List<GeneratedArtifact> generatedArtifacts = new ArrayList<>();
}
