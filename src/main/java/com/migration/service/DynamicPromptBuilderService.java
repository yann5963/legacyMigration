package com.migration.service;

import com.migration.model.ContextReference;
import com.migration.model.GeneratedArtifact;
import com.migration.model.MigrationFileStatus;
import com.migration.model.ProjectConfiguration;
import com.migration.config.MigrationPromptsProperties;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Construit dynamiquement un prompt de conversion Spring Boot 3 à partir des métadonnées
 * de pré-analyse sémantique, des risques détectés et du contexte des dépendances.
 */
@Service
public class DynamicPromptBuilderService {

    private static final String DEFAULT_BASE_PACKAGE = "com.migration.target";

    private final MigrationFileStatusRepository fileStatusRepository;
    private final ProjectConfigurationRepository projectConfigRepository;
    private final MigrationPromptsProperties prompts;
    private final TargetArtifactRegistryService artifactRegistry;

    public DynamicPromptBuilderService(MigrationFileStatusRepository fileStatusRepository,
                                       ProjectConfigurationRepository projectConfigRepository) {
        this(fileStatusRepository, projectConfigRepository, new MigrationPromptsProperties(), null);
    }

    public DynamicPromptBuilderService(MigrationFileStatusRepository fileStatusRepository,
                                       ProjectConfigurationRepository projectConfigRepository,
                                       MigrationPromptsProperties prompts) {
        this(fileStatusRepository, projectConfigRepository, prompts, null);
    }

    @Autowired
    public DynamicPromptBuilderService(MigrationFileStatusRepository fileStatusRepository,
                                       ProjectConfigurationRepository projectConfigRepository,
                                       MigrationPromptsProperties prompts,
                                       TargetArtifactRegistryService artifactRegistry) {
        this.fileStatusRepository = fileStatusRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.prompts = prompts == null ? new MigrationPromptsProperties() : prompts;
        this.artifactRegistry = artifactRegistry;
    }

    /** Prompt de conversion accompagné des artefacts cibles existants injectés dans son contexte. */
    public record ConversionPrompt(String prompt,
                                   List<TargetArtifactRegistryService.ExistingArtifactMatch> existingArtifacts) {
    }

    /**
     * Assemble dynamiquement le prompt de conversion pour un fichier donné et ses dépendances.
     *
     * @param fileStatus        Statut et métadonnées de pré-analyse du fichier source.
     * @param extraContextPaths Chemins supplémentaires ajoutés manuellement dans la modale.
     * @return Prompt structuré prêt à être affiché, édité et exécuté par le LLM.
     */
    public String buildConversionPrompt(MigrationFileStatus fileStatus, List<String> extraContextPaths) {
        return buildConversionPromptWithArtifacts(fileStatus, extraContextPaths).prompt();
    }

    public ConversionPrompt buildConversionPromptWithArtifacts(MigrationFileStatus fileStatus,
                                                               List<String> extraContextPaths) {
        if (fileStatus == null) {
            throw new IllegalArgumentException("fileStatus ne doit pas être null");
        }

        String basePackage = resolveBasePackage();
        String basePackagePath = basePackage.replace('.', '/');
        String role = normalize(fileStatus.getArchitecturalRole());
        String targetArtifact = normalize(fileStatus.getTargetSpringArtifact());

        StringBuilder sb = new StringBuilder();
        sb.append("### Contexte de migration du fichier : ").append(safe(fileStatus.getSourceFile())).append("\n");
        appendMetadataSection(sb, fileStatus, basePackage);
        appendEntryPointContext(sb, fileStatus);
        appendRoleDirectives(sb, role, targetArtifact, fileStatus.getTargetSpringArtifact(), basePackage);
        appendRiskGuardrails(sb, fileStatus.getDetectedRisks());
        appendDependencyContext(sb, fileStatus, extraContextPaths);
        List<TargetArtifactRegistryService.ExistingArtifactMatch> existing =
                appendExistingArtifacts(sb, fileStatus, extraContextPaths);
        appendMultiFileSplittingRules(sb, basePackagePath);

        return new ConversionPrompt(sb.toString().trim(), existing);
    }

    private List<TargetArtifactRegistryService.ExistingArtifactMatch> appendExistingArtifacts(
            StringBuilder sb, MigrationFileStatus fileStatus, List<String> extraContextPaths) {
        if (artifactRegistry == null) {
            return List.of();
        }
        MigrationPromptsProperties.Registry registry = prompts.getConversion().getRegistry();
        List<TargetArtifactRegistryService.ExistingArtifactMatch> matches;
        try {
            matches = artifactRegistry.findRelevantExistingArtifacts(fileStatus, extraContextPaths,
                    registry.getTopK(), registry.getMaxContextChars());
        } catch (Exception ex) {
            return List.of();
        }
        if (matches.isEmpty()) {
            return List.of();
        }
        String rendered = matches.stream()
                .map(TargetArtifactRegistryService.ExistingArtifactMatch::render)
                .collect(Collectors.joining());
        sb.append(prompts.render(prompts.getExistingArtifactsTemplate(), Map.of(
                "count", matches.size(),
                "artifacts", rendered))).append("\n");
        return matches;
    }

    private void appendEntryPointContext(StringBuilder sb, MigrationFileStatus fileStatus) {
        if (fileStatus.isEntryPoint()) {
            sb.append("- **ENTRY POINT** : ce fichier est un point d'entrée de l'application.\n");
        }
        if (!isNotBlank(fileStatus.getRootEntryPoints())) {
            return;
        }
        String roots = fileStatus.getRootEntryPoints().trim();
        String types = normalize(fileStatus.getRootEntryPointTypes());
        String rootNames = normalize(roots);
        sb.append(prompts.getEntryPointHeader()).append("\n").append(roots).append("\n");
        List<String> rootFiles = List.of(rootNames.replace('\\', '/').split(",\\s*"));
        if (types.contains("BATCH") || rootFiles.stream()
                .anyMatch(root -> root.equals("MAIN.JAVA") || root.endsWith("/MAIN.JAVA"))) {
            sb.append(prompts.getEntryPointBatch()).append("\n");
        }
        if (types.contains("WEB") || rootNames.contains("WEB.XML") || rootNames.contains("STRUTS-CONFIG.XML")
                || rootFiles.stream().anyMatch(root -> root.matches(".*(?:SERVLET|ACTION)\\.JAVA"))) {
            sb.append(prompts.getEntryPointWeb()).append("\n");
        }
        sb.append(prompts.getEntryPointShared()).append("\n\n");
    }

    private void appendMetadataSection(StringBuilder sb, MigrationFileStatus fileStatus, String basePackage) {
        sb.append(prompts.render(prompts.getMetadata(), Map.of(
                "role", orDefault(fileStatus.getArchitecturalRole(), "NON_ANALYSE"),
                "artifact", orDefault(fileStatus.getTargetSpringArtifact(), "À déterminer"),
                "complexity", orDefault(fileStatus.getMigrationComplexity(), "INCONNUE"),
                "lot", orDefault(fileStatus.getSuggestedFunctionalLot(), ""),
                "basePackage", basePackage))).append("\n");
        if (isNotBlank(fileStatus.getSuggestedFunctionalLot())) {
            sb.append("- **Lot fonctionnel suggéré** : ").append(fileStatus.getSuggestedFunctionalLot().trim()).append("\n");
        }
        sb.append("- **Package racine cible** : `").append(basePackage).append("`\n");
        if (isNotBlank(fileStatus.getFunctionalSummary())) {
            sb.append("\n#### Résumé fonctionnel et technique\n")
              .append(fileStatus.getFunctionalSummary().trim()).append("\n");
        }
        sb.append("\n");
    }

    private void appendRoleDirectives(StringBuilder sb, String role, String targetArtifact, String rawTargetArtifact, String basePackage) {
        sb.append(prompts.getRoleHeader()).append("\n");
        sb.append(prompts.roleDirective(role, targetArtifact, basePackage));

        if (isNotBlank(rawTargetArtifact)) {
            sb.append("- Artefact principal attendu : `").append(rawTargetArtifact.trim()).append("`.\n");
        }
        sb.append("\n");
    }

    private void appendRiskGuardrails(StringBuilder sb, String detectedRisks) {
        sb.append(prompts.getRiskHeader()).append("\n");
        if (isNotBlank(detectedRisks)) {
            sb.append(prompts.getRiskDetected()).append("\n");
            for (String risk : parseRisks(detectedRisks)) {
                sb.append("- ").append(risk).append("\n");
            }
            sb.append(prompts.getRiskDirectives()).append("\n");
        } else {
            sb.append(prompts.getRiskNone()).append("\n");
        }
        sb.append("\n");
    }

    private void appendDependencyContext(StringBuilder sb, MigrationFileStatus fileStatus, List<String> extraContextPaths) {
        Set<String> allContextPaths = new LinkedHashSet<>();
        if (fileStatus.getContextFiles() != null) {
            for (ContextReference ref : fileStatus.getContextFiles()) {
                if (ref != null && isNotBlank(ref.getFilePath())) {
                    allContextPaths.add(ref.getFilePath().trim());
                }
            }
        }
        if (extraContextPaths != null) {
            for (String extra : extraContextPaths) {
                if (isNotBlank(extra)) {
                    allContextPaths.add(extra.trim());
                }
            }
        }

        if (allContextPaths.isEmpty()) {
            return;
        }

        sb.append("### Contexte des dépendances et fichiers déjà migrés\n");
        for (String path : allContextPaths) {
            Optional<MigrationFileStatus> depOpt = fileStatusRepository.findByAbsolutePath(path);
            sb.append("- **").append(path).append("**");
            if (depOpt.isPresent()) {
                MigrationFileStatus dep = depOpt.get();
                if (isNotBlank(dep.getFunctionalSummary())) {
                    sb.append("\n  - Résumé fonctionnel : ").append(dep.getFunctionalSummary().trim());
                }
                List<String> migratedPaths = extractMigratedTargetPaths(dep);
                if (!migratedPaths.isEmpty()) {
                    sb.append("\n  - Fichiers cibles déjà générés : ").append(String.join(", ", migratedPaths));
                }
            } else {
                sb.append(" (fichier de référence externe)");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void appendMultiFileSplittingRules(StringBuilder sb, String basePackagePath) {
        sb.append(prompts.getSplittingHeader()).append("\n");
        sb.append(prompts.render(prompts.getSplittingRules(), Map.of("basePackagePath", basePackagePath))).append("\n");
    }

    private List<String> extractMigratedTargetPaths(MigrationFileStatus dep) {
        if (dep.getGeneratedArtifacts() != null && !dep.getGeneratedArtifacts().isEmpty()) {
            return dep.getGeneratedArtifacts().stream()
                    .map(GeneratedArtifact::getRelativeTargetPath)
                    .filter(this::isNotBlank)
                    .collect(Collectors.toList());
        }
        if (dep.getTargetFiles() != null && !dep.getTargetFiles().isEmpty()) {
            return dep.getTargetFiles().stream()
                    .filter(this::isNotBlank)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private List<String> parseRisks(String detectedRisks) {
        List<String> result = new ArrayList<>();
        String cleaned = detectedRisks.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        for (String part : cleaned.split("[;\n,]")) {
            String item = part.trim().replaceAll("^[\"']+|[\"']+$", "").trim();
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        if (result.isEmpty() && !detectedRisks.trim().isEmpty()) {
            result.add(detectedRisks.trim());
        }
        return result;
    }

    private String resolveBasePackage() {
        return projectConfigRepository.findById(1L)
                .map(config -> isNotBlank(config.getTargetGroupId())
                        ? config.getTargetGroupId()
                        : config.getTargetBasePackage())
                .filter(this::isNotBlank)
                .map(String::trim)
                .orElse(DEFAULT_BASE_PACKAGE);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String orDefault(String value, String fallback) {
        return isNotBlank(value) ? value.trim() : fallback;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
