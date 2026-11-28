package com.migration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.migration.ai.MigrationAiService;
import com.migration.config.MigrationPromptsProperties;
import com.migration.model.GeneratedArtifact;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.GeneratedArtifactRepository;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectFinalizationService {

    private final GeneratedArtifactRepository artifacts;
    private final ProjectConfigurationRepository configurations;
    private final TargetArtifactRegistryService registry;
    private final MigrationAiService ai;
    private final MigrationPromptsProperties prompts;
    private final ObjectMapper objectMapper;
    private final FinalizationCodeSupport codeSupport;
    private final FinalizationMavenRunner maven;
    private final TargetProjectPreparationService preparation;
    private final ReentrantLock operationLock = new ReentrantLock();

    @Value("${migration.target.output-dir:}")
    private String fallbackOutputDir;

    public record ArtifactView(Long id, String artifactType, String relativeTargetPath,
                               String compilationStatus, LocalDateTime lastProcessedAt,
                               int errorsCount, int fixAttemptsCount, String lastCompilerErrors, boolean locked) { }
    public record BuildSummary(Boolean buildSuccess, int totalErrors, int filesInErrorCount, int compiledFilesCount,
                               LocalDateTime builtAt, String diagnostic, List<ArtifactView> files) { }
    public record PromptPreview(Long artifactId, String compilerErrors, String prompt) { }
    public record FixResult(boolean applied, String message, BuildSummary build) { }

    private ProjectConfiguration configuration() {
        return configurations.findById(1L).orElseThrow(() ->
                new IllegalStateException("Configurez le projet cible avant la finalisation."));
    }

    private Path projectRoot() throws IOException {
        String path = configuration().getTargetProjectPath();
        if (path == null || path.isBlank()) {
            path = fallbackOutputDir;
        }
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Le chemin du projet cible n'est pas configure.");
        }
        Path root = Path.of(path.strip()).toAbsolutePath().normalize();
        Path workspace = Path.of("").toAbsolutePath().normalize();
        if (root.getNameCount() < 2 || root.equals(workspace)
                || (Files.exists(root) && Files.isSameFile(root, workspace))) {
            throw new IllegalArgumentException("Choisissez un repertoire cible distinct de l'outil de migration.");
        }
        return root;
    }

    static Path safeResolve(Path root, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Chemin cible vide.");
        }
        Path relative = Path.of(relativePath.replace('\\', '/'));
        Path target = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Chemin cible hors du projet : " + relativePath);
        }
        if (Files.exists(root)) {
            for (Path parent = target; parent != null && parent.startsWith(root); parent = parent.getParent()) {
                if (Files.exists(parent) && !parent.toRealPath().startsWith(root.toRealPath())) {
                    throw new IllegalArgumentException("Lien symbolique hors du projet : " + relativePath);
                }
            }
        }
        return target;
    }

    public BuildSummary prepareTargetProject() {
        return exclusively(() -> {
            Path root = projectRoot();
            List<GeneratedArtifact> files = orderedArtifacts();
            Map<Long, String> cleaned = new LinkedHashMap<>();
            for (GeneratedArtifact artifact : files) {
                safeResolve(root, artifact.getRelativeTargetPath());
                if (isJava(artifact)) {
                    String updated = codeSupport.clean(artifact.getGeneratedCode(), artifact.getRelativeTargetPath());
                    if (Boolean.TRUE.equals(artifact.getLocked()) && !updated.equals(artifact.getGeneratedCode())) {
                        throw new IllegalStateException("Artefact verrouille a nettoyer : " + artifact.getRelativeTargetPath());
                    }
                    cleaned.put(artifact.getId(), updated);
                }
            }
            preparation.prepare(root, configuration(), files);
            for (GeneratedArtifact artifact : files) {
                String code = cleaned.getOrDefault(artifact.getId(), artifact.getGeneratedCode());
                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException("Artefact vide : " + artifact.getRelativeTargetPath());
                }
                Path target = safeResolve(root, artifact.getRelativeTargetPath());
                if (Boolean.TRUE.equals(artifact.getLocked()) && Files.exists(target)
                        && !Files.readString(target).equals(code)) {
                    throw new IllegalStateException("Fichier verrouille different sur disque : " + target);
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, code);
                artifact.setGeneratedCode(code);
                registry.refreshCompilationMetadata(artifact);
                artifact.setLastProcessedAt(LocalDateTime.now());
                artifact.setCompilationStatus("PREPARED");
                artifact.setLastCompilerErrors(null);
                artifacts.save(artifact);
            }
            ProjectConfiguration config = configuration();
            config.setLastBuildSuccess(null);
            config.setLastBuildAt(null);
            config.setLastBuildErrors(0);
            config.setLastBuildDiagnostic("Preparation terminee. Lancez le build Maven.");
            configurations.save(config);
            return getSummary();
        });
    }

    public BuildSummary runMavenBuildAndAnalyzeErrors() {
        return exclusively(() -> {
            Path root = projectRoot();
            if (!Files.isRegularFile(safeResolve(root, "pom.xml"))) {
                throw new IllegalStateException("pom.xml absent : preparez d'abord le projet.");
            }
            FinalizationMavenRunner.BuildOutput output;
            try {
                output = maven.build(root);
            } catch (IOException | InterruptedException ex) {
                ProjectConfiguration config = configuration();
                config.setLastBuildSuccess(false);
                config.setLastBuildAt(LocalDateTime.now());
                config.setLastBuildDiagnostic(ex.getMessage());
                configurations.save(config);
                throw ex;
            }
            List<FinalizationCodeSupport.CompilerError> errors = codeSupport.parseErrors(output.output());
            List<GeneratedArtifact> files = orderedArtifacts();
            int mappedErrors = 0;
            for (GeneratedArtifact artifact : files) {
                Path target = safeResolve(root, artifact.getRelativeTargetPath());
                List<FinalizationCodeSupport.CompilerError> localErrors = new ArrayList<>();
                for (var error : errors) {
                    Path errorPath = Path.of(error.path());
                    if (!errorPath.isAbsolute()) {
                        errorPath = root.resolve(errorPath);
                    }
                    if (errorPath.toAbsolutePath().normalize().equals(target)) {
                        localErrors.add(error);
                    }
                }
                mappedErrors += localErrors.size();
                artifact.setLastProcessedAt(LocalDateTime.now());
                if (!localErrors.isEmpty()) {
                    artifact.setLastCompilerErrors(localErrors.stream()
                            .map(FinalizationCodeSupport.CompilerError::render).collect(Collectors.joining("\n")));
                    artifact.setCompilationStatus(artifact.getFixAttemptsCount() >= 2
                            || "NEEDS_MANUAL_REVIEW".equals(artifact.getCompilationStatus())
                            || Boolean.TRUE.equals(artifact.getLocked()) ? "NEEDS_MANUAL_REVIEW" : "COMPILATION_ERROR");
                } else if ((output.success() || !errors.isEmpty()) && Files.isRegularFile(target)) {
                    artifact.setLastCompilerErrors(null);
                    artifact.setCompilationStatus("COMPILING_OK");
                } else {
                    artifact.setLastCompilerErrors(null);
                    // Infrastructure failures without compiler diagnostics cannot establish per-file success.
                    artifact.setCompilationStatus("PENDING");
                }
                artifacts.save(artifact);
            }
            ProjectConfiguration config = configuration();
            config.setLastBuildSuccess(output.success());
            config.setLastBuildAt(LocalDateTime.now());
            config.setLastBuildErrors(errors.size());
            String diagnostic = output.success() ? "BUILD SUCCESS" : output.output().lines()
                    .filter(line -> line.contains("[ERROR]")).limit(15).collect(Collectors.joining("\n"));
            if (!output.success() && diagnostic.isBlank()) {
                diagnostic = output.output().lines().skip(Math.max(0, output.output().lines().count() - 12))
                        .collect(Collectors.joining("\n"));
            }
            if (mappedErrors < errors.size()) {
                diagnostic = (errors.size() - mappedErrors) + " erreur(s) hors des artefacts enregistres.\n" + diagnostic;
            }
            config.setLastBuildDiagnostic(diagnostic.substring(0, Math.min(4000, diagnostic.length())));
            configurations.save(config);
            return getSummary();
        });
    }

    public PromptPreview getFixPrompt(Long artifactId) {
        GeneratedArtifact artifact = artifact(artifactId);
        requireFixable(artifact);
        String prompt = artifact.getLastUsedFixPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = buildFixPrompt(artifact);
        }
        return new PromptPreview(artifactId, artifact.getLastCompilerErrors(), prompt);
    }

    private String buildFixPrompt(GeneratedArtifact artifact) {
        if (prompts.getFinalization().getUserTemplate().isBlank()) {
            throw new IllegalStateException("Le template de finalisation n'est pas configure dans prompts.yml.");
        }
        String compilerErrors = artifact.getLastCompilerErrors();
        if (compilerErrors.length() > 8000) {
            throw new IllegalArgumentException("Trop d'erreurs pour un prompt leger : correction manuelle requise.");
        }
        return prompts.render(prompts.getFinalization().getUserTemplate(), Map.of(
                "fileName", artifact.getRelativeTargetPath(),
                "compilerErrors", compilerErrors,
                "codeSlices", codeSupport.codeSlices(artifact.getGeneratedCode(), codeSupport.parseErrors(compilerErrors)),
                "referencedSignatures", registry.findReferencedSignatures(compilerErrors, artifact.getId())));
    }

    public FixResult fixSingleArtifact(Long artifactId, String customPrompt, String modelOverride) {
        return exclusively(() -> {
            GeneratedArtifact artifact = artifact(artifactId);
            requireFixable(artifact);
            Path target = safeResolve(projectRoot(), artifact.getRelativeTargetPath());
            if (!Files.isRegularFile(target) || !Files.readString(target).equals(artifact.getGeneratedCode())) {
                throw new IllegalStateException("Le fichier sur disque differe de l'artefact. Preparez ou reconciliez le projet.");
            }
            String prompt = customPrompt == null || customPrompt.isBlank() ? buildFixPrompt(artifact) : customPrompt;
            if (prompts.getFinalization().getSystem().isBlank()) {
                throw new IllegalStateException("Le prompt systeme de finalisation n'est pas configure.");
            }
            if (prompt.length() > 36000) {
                throw new IllegalArgumentException("Prompt trop volumineux (maximum 36000 caracteres).");
            }
            artifact.setFixAttemptsCount(artifact.getFixAttemptsCount() + 1);
            artifact.setLastUsedFixPrompt(prompt);
            artifact.setLastProcessedAt(LocalDateTime.now());
            artifacts.save(artifact);
            boolean applied = false;
            String message;
            try {
                String response = ai.analyzeAndRefactor("{finalizationSystem}",
                        Map.of("finalizationSystem", prompts.getFinalization().getSystem()),
                        prompt, null, modelOverride);
                String slices = codeSupport.codeSlices(artifact.getGeneratedCode(),
                        codeSupport.parseErrors(artifact.getLastCompilerErrors()));
                String updated = codeSupport.applyPatch(artifact.getGeneratedCode(), response, objectMapper, slices);
                if (!Files.readString(target).equals(artifact.getGeneratedCode())) {
                    throw new IllegalStateException("Le fichier a change pendant la correction IA.");
                }
                Files.writeString(target, updated);
                artifact.setGeneratedCode(updated);
                registry.refreshCompilationMetadata(artifact);
                artifact.setCompilationStatus("FIXED_BY_AI");
                applied = true;
                message = "Patch IA applique et verification Maven terminee.";
            } catch (RuntimeException | IOException ex) {
                log.warn("Correction refusee pour {}", artifact.getRelativeTargetPath(), ex);
                artifact.setCompilationStatus("NEEDS_MANUAL_REVIEW");
                message = "Correction non appliquee : " + ex.getMessage();
            }
            artifact.setLastProcessedAt(LocalDateTime.now());
            artifacts.save(artifact);
            BuildSummary summary = runMavenBuildAndAnalyzeErrors();
            if (!applied) {
                GeneratedArtifact rejected = artifact(artifactId);
                rejected.setCompilationStatus("NEEDS_MANUAL_REVIEW");
                artifacts.save(rejected);
                summary = getSummary();
            }
            if (applied && !Boolean.TRUE.equals(summary.buildSuccess())) {
                message = "Patch IA applique ; le build Maven reste en echec. Consultez le diagnostic.";
            }
            return new FixResult(applied, message, summary);
        });
    }

    public BuildSummary runStepByStepAutoFix(int maxIterations) {
        return runStepByStepAutoFix(maxIterations, ignored -> { });
    }

    public BuildSummary runStepByStepAutoFix(int maxIterations, Consumer<String> progress) {
        if (maxIterations < 1 || maxIterations > 50) {
            throw new IllegalArgumentException("maxIterations doit etre compris entre 1 et 50.");
        }
        return exclusively(() -> {
            progress.accept("Diagnostic initial Maven");
            BuildSummary summary = runMavenBuildAndAnalyzeErrors();
            for (int pass = 1; pass <= maxIterations && !Boolean.TRUE.equals(summary.buildSuccess()); pass++) {
                GeneratedArtifact next = orderedArtifacts().stream()
                        .filter(a -> "COMPILATION_ERROR".equals(a.getCompilationStatus()))
                        .filter(a -> a.getFixAttemptsCount() < 2 && !Boolean.TRUE.equals(a.getLocked()) && isJava(a))
                        .findFirst().orElse(null);
                if (next == null) {
                    break;
                }
                progress.accept("Saut " + pass + "/" + maxIterations + " : " + next.getRelativeTargetPath());
                try {
                    summary = fixSingleArtifact(next.getId(), null, null).build();
                } catch (IllegalArgumentException ex) {
                    log.warn("Contexte de correction refuse pour {}", next.getRelativeTargetPath(), ex);
                    next.setCompilationStatus("NEEDS_MANUAL_REVIEW");
                    next.setLastProcessedAt(LocalDateTime.now());
                    artifacts.save(next);
                    summary = getSummary();
                }
            }
            return summary;
        });
    }

    public BuildSummary getSummary() {
        ProjectConfiguration config = configuration();
        List<ArtifactView> files = orderedArtifacts().stream().map(a -> new ArtifactView(
                a.getId(), a.getArtifactType(), a.getRelativeTargetPath(),
                a.getCompilationStatus() == null ? "PENDING" : a.getCompilationStatus(), a.getLastProcessedAt(),
                a.getLastCompilerErrors() == null ? 0 : codeSupport.parseErrors(a.getLastCompilerErrors()).size(),
                a.getFixAttemptsCount(), a.getLastCompilerErrors(), Boolean.TRUE.equals(a.getLocked()))).toList();
        return new BuildSummary(config.getLastBuildSuccess(), config.getLastBuildErrors(),
                (int) files.stream().filter(a -> a.errorsCount() > 0).count(),
                (int) files.stream().filter(a -> "COMPILING_OK".equals(a.compilationStatus())).count(),
                config.getLastBuildAt(), config.getLastBuildDiagnostic(), files);
    }

    private GeneratedArtifact artifact(Long id) {
        return artifacts.findById(id).orElseThrow(() -> new IllegalArgumentException("Artefact introuvable : " + id));
    }

    private void requireFixable(GeneratedArtifact artifact) {
        if (!isJava(artifact) || artifact.getLastCompilerErrors() == null || artifact.getLastCompilerErrors().isBlank()) {
            throw new IllegalArgumentException("Lancez un diagnostic Maven : aucune erreur Java pour ce fichier.");
        }
        if (Boolean.TRUE.equals(artifact.getLocked())) {
            throw new IllegalArgumentException("Cet artefact est verrouille.");
        }
    }

    private boolean isJava(GeneratedArtifact artifact) {
        return artifact.getRelativeTargetPath() != null && artifact.getRelativeTargetPath().endsWith(".java");
    }

    private List<GeneratedArtifact> orderedArtifacts() {
        return artifacts.findAll().stream().sorted(Comparator
                .comparingInt((GeneratedArtifact a) -> priority(a.getArtifactType()))
                .thenComparing(GeneratedArtifact::getId)).toList();
    }

    static int priority(String type) {
        if (type == null) {
            return 5;
        }
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "ENTITY", "DTO", "MODEL" -> 0;
            case "REPOSITORY", "DAO" -> 1;
            case "SERVICE" -> 2;
            case "CONTROLLER" -> 3;
            default -> 4;
        };
    }

    private interface Operation<T> { T run() throws Exception; }

    private <T> T exclusively(Operation<T> operation) {
        if (!operationLock.tryLock()) {
            throw new IllegalStateException("Une operation de finalisation est deja en cours.");
        }
        try {
            return operation.run();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Finalisation interrompue.", ex);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Echec de la finalisation du projet", ex);
            throw new IllegalStateException("Finalisation impossible : " + ex.getMessage(), ex);
        } finally {
            operationLock.unlock();
        }
    }
}
