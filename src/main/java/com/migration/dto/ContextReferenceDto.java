package com.migration.dto;

import com.migration.model.ContextMode;
import lombok.Data;

@Data
public class ContextReferenceDto {
    private String filePath;
    private ContextMode mode;
}
