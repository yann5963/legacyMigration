package com.migration.controller;

import com.migration.dto.RetryRequest;
import com.migration.model.GeneratedArtifact;
import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.service.AsyncMigrationRetryService;
import com.migration.service.DynamicPromptBuilderService;
import com.migration.service.DynamicRoutingService;
import com.migration.service.SemanticPreAnalysisService;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.document.Document;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 
 * DashboardController
 */
@RestController
@RequestMapping("/api/migration/files")
@RequiredArgsConstructor
public class DashboardController {

    private final MigrationFileStatusRepository repository;
    private final AsyncMigrationRetryService retryService;
    private final SemanticPreAnalysisService preAnalysisService;
    private final DynamicPromptBuilderService dynamicPromptBuilderService;
    private final DynamicRoutingService dynamicRoutingService;
    private final VectorStore vectorStore;

    /**
     * Récupère la liste de tous les fichiers de migration.
     * @return
     */
    @GetMapping
    public List<MigrationFileStatus> getAllFiles() {
        return repository.findAll();
    }

    /**
     * Déclenche de manière asynchrone la pré-analyse sémantique d'un fichier spécifique.
     */
    @PostMapping("/{id}/pre-analyze")
    public ResponseEntity<Void> preAnalyzeFile(@PathVariable Long id,
            @RequestParam(required = false) String llmModel) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        preAnalysisService.analyzeFile(id, llmModel);
        return ResponseEntity.accepted().build();
    }

    /**
     * Génère un aperçu du prompt de conversion dynamique avant exécution.
     */
    @PostMapping("/{id}/prompt-preview")
    public ResponseEntity<Map<String, Object>> previewConversionPrompt(
            @PathVariable Long id,
            @RequestBody(required = false) List<String> extraContextPaths) {
        return repository.findById(id)
                .map(status -> ResponseEntity.ok(toPreview(status, extraContextPaths)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Prévisualise le prompt dynamique et les artefacts cibles existants détectés (fqcn, type, raison).
     */
    @GetMapping("/{id}/dynamic-prompt")
    public ResponseEntity<Map<String, Object>> getDynamicPrompt(
            @PathVariable Long id,
            @RequestParam(required = false) List<String> extraContextPaths) {
        return repository.findById(id)
                .map(status -> ResponseEntity.ok(toPreview(status, extraContextPaths)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> toPreview(MigrationFileStatus status, List<String> extraContextPaths) {
        DynamicPromptBuilderService.ConversionPrompt result =
                dynamicPromptBuilderService.buildConversionPromptWithArtifacts(status, extraContextPaths);
        return Map.of("prompt", result.prompt(), "existingArtifacts", result.existingArtifacts());
    }

    /**
     * Convertit un fichier de migration en utilisant le service de conversion asynchrone.
     * @param id
     * @param request
     * @return
     */
    @PostMapping("/{id}/convert")
    public ResponseEntity<Void> convertMigration(@PathVariable Long id,
            @RequestBody(required = false) RetryRequest request) {
        String customPrompt = (request != null) ? request.getCustomPrompt() : null;
        List<com.migration.dto.ContextReferenceDto> contextFiles = (request != null) ? request.getContextFiles() : null;
        String llmModel = (request != null) ? request.getLlmModel() : null;
        retryService.convertFile(id, customPrompt, contextFiles, llmModel);
        return ResponseEntity.accepted().build();
    }

    /**
     * Récupère le contenu d'un fichier de migration, y compris le code original, le code source,
     * le code de test et la liste complète des artefacts générés.
     * @param id
     * @return
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<Map<String, Object>> getFileContent(@PathVariable Long id) {
        return repository.findById(id).map(status -> {
            String originalCode = "Non généré.";
            String sourceCode = "Non généré.";
            String testCode = "Non généré.";

            if (status.getAbsolutePath() != null) {
                Path originalPath = Paths.get(status.getAbsolutePath());
                if (Files.exists(originalPath)) {
                    originalCode = readFileSafely(originalPath);
                } else {
                    originalCode = "Fichier introuvable sur le disque : " + status.getAbsolutePath();
                }
            }

            List<GeneratedArtifact> artifacts = status.getGeneratedArtifacts() != null
                    ? status.getGeneratedArtifacts()
                    : List.of();

            if (!artifacts.isEmpty()) {
                sourceCode = artifacts.get(0).getGeneratedCode() != null
                        ? artifacts.get(0).getGeneratedCode()
                        : sourceCode;
                if (artifacts.size() > 1 && artifacts.get(1).getGeneratedCode() != null) {
                    testCode = artifacts.get(1).getGeneratedCode();
                }
            } else {
                List<String> targetFiles = status.getTargetFiles();
                if (targetFiles != null && !targetFiles.isEmpty()) {
                    Path p0 = Paths.get(targetFiles.get(0));
                    if (Files.exists(p0)) {
                        sourceCode = readFileSafely(p0);
                    }
                    if (targetFiles.size() > 1) {
                        Path p1 = Paths.get(targetFiles.get(1));
                        if (Files.exists(p1)) {
                            testCode = readFileSafely(p1);
                        }
                    }
                }
            }

            return ResponseEntity.ok(Map.<String, Object>of(
                    "originalCode", originalCode,
                    "sourceCode", sourceCode,
                    "testCode", testCode,
                    "artifacts", artifacts));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Lit le contenu d'un fichier de manière sécurisée, en essayant d'abord UTF-8 puis Windows-1252 si nécessaire.
     * @param path
     * @return
     */
    private String readFileSafely(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            CharsetDecoder utf8Decoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString().trim();
            } catch (CharacterCodingException e) {
                return new String(bytes, Charset.forName("Windows-1252")).trim();
            }
        } catch (IOException e) {
            return "Erreur de lecture : " + e.getMessage();
        }
    }

    /**
     * Sauvegarde les artefacts générés au bon emplacement selon la configuration du projet (Group ID et chemin cible),
     * met à jour le statut de validation et ajoute éventuellement le contenu d'entraînement au vector store.
     * @param id
     * @return
     */
    @PostMapping("/{id}/validate")
    public ResponseEntity<?> validateMigration(@PathVariable Long id) {
        return repository.findById(id).map(status -> {
            try {
                dynamicRoutingService.saveArtifacts(status);
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "Erreur lors de la sauvegarde des fichiers : " + e.getMessage()));
            }

            status.setStatus(MigrationStatus.VALIDATED);
            status.setValidated(true);
            if (status.getGeneratedArtifacts() != null) {
                status.getGeneratedArtifacts().forEach(a -> a.setValidated(true));
            }

            if (status.getLastCustomPrompt() != null && !status.getLastCustomPrompt().isEmpty()) {
                try {
                    Path filePath = Paths.get(status.getAbsolutePath());
                    String sourceCode = Files.exists(filePath) ? Files.readString(filePath) : "Unknown source";
                    String trainingContent = "Source: \n" + sourceCode + "\n\nInstruction: "
                            + status.getLastCustomPrompt() + "\n\n(Target code is linked in the project output)";

                    Document doc = new Document(trainingContent,
                            Map.of("type", "few_shot", "sourceFile", status.getSourceFile()));
                    vectorStore.add(List.of(doc));
                } catch (Exception e) {
                    // Ignored for now
                }
            }

            repository.save(status);
            return ResponseEntity.ok().build();
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Réessaie une migration en utilisant éventuellement un prompt personnalisé, des fichiers contextuels et un modèle LLM spécifique.
     * @param id
     * @param request
     * @return
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retryMigration(@PathVariable Long id,
            @RequestBody(required = false) RetryRequest request) {
        String customPrompt = (request != null) ? request.getCustomPrompt() : null;
        List<com.migration.dto.ContextReferenceDto> contextFiles = (request != null) ? request.getContextFiles() : null;
        String llmModel = (request != null) ? request.getLlmModel() : null;
        retryService.convertFile(id, customPrompt, contextFiles, llmModel);
        return ResponseEntity.accepted().build();
    }
}
