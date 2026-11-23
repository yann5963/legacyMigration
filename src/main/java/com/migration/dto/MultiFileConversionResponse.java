package com.migration.dto;

import java.util.List;

/**
 * Réponse structurée multi-fichiers (1 -> N) retournée par le LLM lors de la conversion.
 */
public record MultiFileConversionResponse(
        String architecturalNotes,
        List<TargetFileItem> artifacts
) {
    public record TargetFileItem(
            String relativeTargetPath,
            String artifactType,
            String code
    ) {}
}
