package com.migration.dto;

import lombok.Data;
import java.util.List;

/**
 * Représente une requête de retry dans le processus de migration.
 * RetryRequest
 */
@Data
public class RetryRequest {
    private String customPrompt;
    private List<ContextReferenceDto> contextFiles;
    private String llmModel;
}
