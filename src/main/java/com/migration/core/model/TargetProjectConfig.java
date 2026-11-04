package com.migration.core.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "migration.target")
@Data
public class TargetProjectConfig {
    private String baseDir;
    private String basePackage;
}
