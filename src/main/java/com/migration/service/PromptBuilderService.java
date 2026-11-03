package com.migration.service;

import com.migration.model.ContextMode;
import com.migration.model.ContextReference;
import com.migration.parser.LegacyCodeParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromptBuilderService {

    private final LegacyCodeParserService parserService;

    public String resolveContextFiles(List<ContextReference> contextFiles) {
        if (contextFiles == null || contextFiles.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n### Fichiers de référence pertinents\n");
        sb.append("Utilise ces fichiers de référence comme source de vérité pour les types, contrats, annotations et conventions. Ne génère QUE le code du fichier cible demandé.\n\n");
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
