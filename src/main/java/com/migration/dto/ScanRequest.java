package com.migration.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * Représente une requête de scan dans le processus de migration.
 * ScanRequest
 */
@Data
public class ScanRequest {
    private String sourceRootPath;
    private String defaultLlmModel;
    private List<String> manualEntryPoints = new ArrayList<>();
    private boolean autoDetectEntryPoints = true;
}
