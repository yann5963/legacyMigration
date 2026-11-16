package com.migration.dto;

import com.migration.model.ContextMode;
import lombok.Data;

/**
 * Représente une référence de contexte dans le processus de migration.
 * ContextReferenceDto
 */
@Data
public class ContextReferenceDto {
    private String filePath;
    private ContextMode mode;
}
