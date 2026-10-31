package com.migration.model;

import lombok.Data;
import java.util.List;

@Data
public class InitializrRequest {
    private List<String> dependencies;
}
