package com.migration.dto;

import lombok.Data;

@Data
public class ScanRequest {
    private String sourceRootPath;
    private String defaultLlmModel;
}
