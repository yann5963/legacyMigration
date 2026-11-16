package com.migration.dto;

import lombok.Data;

/**
 * Représente une requête de scan dans le processus de migration.
 * ScanRequest
 */
@Data
public class ScanRequest {
    private String sourceRootPath;
    private String defaultLlmModel;
}
