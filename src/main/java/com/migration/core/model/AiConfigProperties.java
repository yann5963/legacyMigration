package com.migration.core.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration des propriétés pour l'IA dans la migration.
 * AiConfigProperties
 */
@Configuration
@ConfigurationProperties(prefix = "migration.ai")
@Data
public class AiConfigProperties {
    private List<String> availableModels;
}
