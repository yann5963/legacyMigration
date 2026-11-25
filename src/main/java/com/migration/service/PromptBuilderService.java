package com.migration.service;

import com.migration.model.ContextMode;
import com.migration.model.ContextReference;
import com.migration.parser.LegacyCodeParserService;
import com.migration.config.MigrationPromptsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Service pour construire les prompts en utilisant les fichiers de contexte pertinents.
 * PromptBuilderService
 */
@Service
@Slf4j
public class PromptBuilderService {

    private final LegacyCodeParserService parserService;
    private final MigrationPromptsProperties prompts;

    public PromptBuilderService(LegacyCodeParserService parserService) {
        this(parserService, new MigrationPromptsProperties());
    }

    public PromptBuilderService(LegacyCodeParserService parserService, MigrationPromptsProperties prompts) {
        this.parserService = parserService;
        this.prompts = prompts == null ? new MigrationPromptsProperties() : prompts;
    }

    /**
     * Résout les fichiers de contexte pertinents et construit un prompt à partir de leur contenu.
     * @param contextFiles
     * @return
     */
    public String resolveContextFiles(List<ContextReference> contextFiles) {
        if (contextFiles == null || contextFiles.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(prompts.getContextHeader()).append("\n\n");
        for (ContextReference ref : contextFiles) {
            try {
                Path path = Paths.get(ref.getFilePath());
                if (Files.exists(path)) {
                    String content = Files.readString(path);
                    if (ref.getMode() == ContextMode.SIGNATURE_ONLY) {
                        content = parserService.extractSignatures(content);
                    }
                    sb.append("Fichier: ").append(ref.getFilePath()).append(" (Mode: ").append(ref.getMode()).append(")\n");
                    sb.append("```java\n").append(content).append("\n```\n\n");
                }
            } catch (Exception e) {
                log.warn("Could not read context file: {}", ref.getFilePath(), e);
            }
        }
        return sb.toString();
    }
}
