package com.migration.dto;

import lombok.Data;
import java.util.List;

@Data
public class RetryRequest {
    private String customPrompt;
    private List<ContextReferenceDto> contextFiles;
}
