package com.migration.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Référence à un fichier de contexte utilisé lors de la migration.
 * ContextReference
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String filePath;

    @Enumerated(EnumType.STRING)
    private ContextMode mode;
}
