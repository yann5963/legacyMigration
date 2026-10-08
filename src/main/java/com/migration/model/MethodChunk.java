package com.migration.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodChunk {
    private String methodName;
    private String originalSignature;
    private String originalCode;
    private String convertedCode;
    private ChunkStatus status;
}
