package com.migration.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.migration.config.MigrationPromptsProperties;
import com.migration.model.ContextReference;
import com.migration.model.GeneratedArtifact;
import com.migration.model.MigrationFileStatus;
import com.migration.parser.CompactSignatureExtractor;
import com.migration.repository.GeneratedArtifactRepository;
import com.migration.repository.MigrationFileStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Registre des artefacts cibles générés : upsert relationnel par FQCN, indexation pgvector
 * et recherche hybride (graphe / table SQL / similarité) plafonnée par un budget de caractères.
 */
@Service
@Slf4j
public class TargetArtifactRegistryService {

    public static final String DOC_TYPE = "TARGET_ARTIFACT";
    public static final String REASON_GRAPH = "Graphe";
    public static final String REASON_SQL_TABLE = "Table SQL";
    public static final String REASON_SIMILARITY = "Similarité PgVector";

    private static final Set<String> REGISTERED_TYPES = Set.of("ENTITY", "DTO", "REPOSITORY", "SERVICE", "CONTROLLER");
    private static final int MAX_LEGACY_SOURCE_CHARS = 200_000;
    private static final int MAX_QUERY_CHARS = 1_000;
    private static final int MAX_NAMES_IN_DESCRIPTION = 30;
    private static final int MIN_TRUNCATED_SIGNATURE_CHARS = 80;

    private final GeneratedArtifactRepository artifactRepository;
    private final MigrationFileStatusRepository fileStatusRepository;
    private final VectorStore vectorStore;
    private final CompactSignatureExtractor extractor;
    private final MigrationPromptsProperties prompts;

    public TargetArtifactRegistryService(GeneratedArtifactRepository artifactRepository,
                                         MigrationFileStatusRepository fileStatusRepository,
                                         VectorStore vectorStore,
                                         CompactSignatureExtractor extractor,
                                         MigrationPromptsProperties prompts) {
        this.artifactRepository = artifactRepository;
        this.fileStatusRepository = fileStatusRepository;
        this.vectorStore = vectorStore;
        this.extractor = extractor;
        this.prompts = prompts;
    }

    /** Artefact cible détecté ; la signature compacte n'est jamais sérialisée vers l'UI. */
    public record ExistingArtifactMatch(String fqcn, String artifactType, String matchReason, String sqlTableName,
                                        @JsonIgnore String compactSignature) {

        public String render() {
            StringBuilder sb = new StringBuilder("- `").append(fqcn).append("` (").append(artifactType);
            if (sqlTableName != null && !sqlTableName.isBlank()) {
                sb.append(", table ").append(sqlTableName);
            }
            sb.append(")\n```java\n");
            if (compactSignature != null && !compactSignature.isBlank()) {
                sb.append(compactSignature.strip()).append('\n');
            }
            return sb.append("```\n").toString();
        }

        ExistingArtifactMatch withSignature(String signature) {
            return new ExistingArtifactMatch(fqcn, artifactType, matchReason, sqlTableName, signature);
        }
    }

    /**
     * Enrichit et upsert les artefacts d'un fichier converti, puis les indexe dans pgvector.
     * Un échec d'embedding ne fait jamais échouer la conversion.
     */
    public void registerOrUpdateArtifacts(MigrationFileStatus status) {
        if (status == null || status.getGeneratedArtifacts() == null) {
            return;
        }
        for (GeneratedArtifact artifact : new ArrayList<>(status.getGeneratedArtifacts())) {
            if (artifact == null || artifact.getId() == null || !isRegistrable(artifact)) {
                continue;
            }
            try {
                registerOne(status, artifact);
            } catch (Exception ex) {
                log.warn("Enregistrement de l'artefact {} dans le registre impossible.",
                        artifact.getRelativeTargetPath(), ex);
            }
        }
    }

    private void registerOne(MigrationFileStatus status, GeneratedArtifact artifact) {
        CompactSignatureExtractor.Extraction extraction = extractor.extract(
                artifact.getGeneratedCode(), artifact.getArtifactType(), artifact.getRelativeTargetPath());
        if (extraction.fqcn() == null || extraction.fqcn().isBlank()) {
            return;
        }
        String description = buildSemanticDescription(extraction, artifact, status);
        LocalDateTime now = LocalDateTime.now();

        applyMetadata(artifact, extraction, description, now);
        artifactRepository.save(artifact);

        GeneratedArtifact canonical = artifactRepository.findByFqcnOrderByIdAsc(extraction.fqcn()).stream()
                .filter(a -> !a.getId().equals(artifact.getId()))
                .findFirst()
                .orElse(artifact);
        if (canonical != artifact) {
            if (Boolean.TRUE.equals(canonical.getLocked())) {
                log.info("Artefact {} verrouillé : upsert ignoré.", extraction.fqcn());
                return;
            }
            canonical.setGeneratedCode(artifact.getGeneratedCode());
            applyMetadata(canonical, extraction, description, now);
            artifactRepository.save(canonical);
        }
        indexInVectorStore(canonical, extraction, description);
    }

    private void applyMetadata(GeneratedArtifact target, CompactSignatureExtractor.Extraction extraction,
                               String description, LocalDateTime now) {
        target.setFqcn(extraction.fqcn());
        target.setSqlTableName(extraction.sqlTableName());
        target.setCompactSignature(extraction.compactSignature());
        target.setSemanticDescription(description);
        target.setUpdatedAt(now);
        target.setLastProcessedAt(now);
        target.setCompilationStatus("PENDING");
        target.setLastCompilerErrors(null);
    }

    private void indexInVectorStore(GeneratedArtifact canonical, CompactSignatureExtractor.Extraction extraction,
                                    String description) {
        try {
            String id = documentId(extraction.fqcn());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("artifactId", String.valueOf(canonical.getId()));
            metadata.put("fqcn", extraction.fqcn());
            metadata.put("artifactType", canonical.getArtifactType() == null ? "" : canonical.getArtifactType());
            metadata.put("sqlTableName", extraction.sqlTableName() == null ? "" : extraction.sqlTableName());
            metadata.put("docType", DOC_TYPE);
            vectorStore.delete(List.of(id));
            vectorStore.add(List.of(new Document(id, description, metadata)));
        } catch (Exception ex) {
            log.warn("Indexation pgvector de {} impossible (service d'embedding indisponible ?) : {}",
                    extraction.fqcn(), ex.getMessage());
        }
    }

    private String buildSemanticDescription(CompactSignatureExtractor.Extraction extraction,
                                            GeneratedArtifact artifact, MigrationFileStatus status) {
        StringBuilder sb = new StringBuilder(extraction.fqcn())
                .append(" | type: ").append(artifact.getArtifactType());
        if (extraction.sqlTableName() != null) {
            sb.append(" | table: ").append(extraction.sqlTableName());
        }
        if (status.getSuggestedFunctionalLot() != null && !status.getSuggestedFunctionalLot().isBlank()) {
            sb.append(" | lot: ").append(status.getSuggestedFunctionalLot().trim());
        }
        if (!extraction.fieldNames().isEmpty()) {
            sb.append(" | champs: ").append(limit(extraction.fieldNames()));
        }
        if (!extraction.methodNames().isEmpty()) {
            sb.append(" | méthodes: ").append(limit(extraction.methodNames()));
        }
        return sb.toString();
    }

    private String limit(List<String> names) {
        return names.stream().limit(MAX_NAMES_IN_DESCRIPTION).collect(Collectors.joining(", "));
    }

    public String findReferencedSignatures(String compilerErrors, Long excludedArtifactId) {
        Map<String, ExistingArtifactMatch> matches = new LinkedHashMap<>();
        for (GeneratedArtifact artifact : artifactRepository.findAll()) {
            String fqcn = artifact.getFqcn();
            if (artifact.getId().equals(excludedArtifactId) || fqcn == null || !isRegistrable(artifact)) {
                continue;
            }
            String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            if (Pattern.compile("(?<![\\w$])" + Pattern.quote(simpleName) + "(?![\\w$])")
                    .matcher(compilerErrors).find()) {
                if (artifact.getCompactSignature() != null && !artifact.getCompactSignature().isBlank()) {
                    matches.putIfAbsent(fqcn, new ExistingArtifactMatch(fqcn, artifact.getArtifactType(),
                            "Compilation", artifact.getSqlTableName(), artifact.getCompactSignature()));
                }
            }
        }
        return applyBudget(sortByPriority(matches), prompts.getConversion().getRegistry().getMaxContextChars())
                .stream().map(ExistingArtifactMatch::render).collect(Collectors.joining("\n"));
    }

    public void refreshCompilationMetadata(GeneratedArtifact artifact) {
        CompactSignatureExtractor.Extraction extraction = extractor.extract(
                artifact.getGeneratedCode(), artifact.getArtifactType(), artifact.getRelativeTargetPath());
        artifact.setFqcn(extraction.fqcn());
        artifact.setCompactSignature(extraction.compactSignature());
        artifact.setSqlTableName(extraction.sqlTableName());
        artifact.setUpdatedAt(LocalDateTime.now());
    }

    public List<ExistingArtifactMatch> findRelevantExistingArtifacts(MigrationFileStatus sourceFile, int topK,
                                                                     int maxBudgetChars) {
        return findRelevantExistingArtifacts(sourceFile, List.of(), topK, maxBudgetChars);
    }

    /**
     * Recherche hybride : graphe de dépendances, tables SQL citées, puis similarité pgvector.
     * Résultat dédupliqué par FQCN (ENTITY/DTO d'abord) et tronqué à {@code maxBudgetChars}.
     */
    public List<ExistingArtifactMatch> findRelevantExistingArtifacts(MigrationFileStatus sourceFile,
                                                                     List<String> extraContextPaths, int topK,
                                                                     int maxBudgetChars) {
        if (sourceFile == null || maxBudgetChars <= 0) {
            return List.of();
        }
        Map<String, ExistingArtifactMatch> found = new LinkedHashMap<>();
        try {
            collectFromGraph(sourceFile, extraContextPaths, found);
            collectFromSqlTables(sourceFile, found);
        } catch (Exception ex) {
            log.warn("Recherche déterministe des artefacts existants impossible : {}", ex.getMessage());
        }
        try {
            collectFromSimilarity(sourceFile, topK, found);
        } catch (Exception ex) {
            log.warn("Recherche pgvector indisponible, repli sur la recherche déterministe : {}", ex.getMessage());
        }
        return applyBudget(sortByPriority(found), maxBudgetChars);
    }

    private void collectFromGraph(MigrationFileStatus sourceFile, List<String> extraContextPaths,
                                  Map<String, ExistingArtifactMatch> found) {
        Set<String> paths = new java.util.LinkedHashSet<>();
        if (sourceFile.getContextFiles() != null) {
            for (ContextReference ref : sourceFile.getContextFiles()) {
                if (ref != null && ref.getFilePath() != null && !ref.getFilePath().isBlank()) {
                    paths.add(ref.getFilePath().trim());
                }
            }
        }
        if (extraContextPaths != null) {
            extraContextPaths.stream().filter(p -> p != null && !p.isBlank()).map(String::trim).forEach(paths::add);
        }
        for (String path : paths) {
            fileStatusRepository.findByAbsolutePath(path).ifPresent(dependency -> {
                if (dependency.getGeneratedArtifacts() == null) {
                    return;
                }
                for (GeneratedArtifact artifact : dependency.getGeneratedArtifacts()) {
                    addMatch(found, sourceFile, artifact, REASON_GRAPH);
                }
            });
        }
    }

    private void collectFromSqlTables(MigrationFileStatus sourceFile, Map<String, ExistingArtifactMatch> found) {
        List<GeneratedArtifact> entities = artifactRepository.findByArtifactTypeAndSqlTableNameIsNotNull("ENTITY");
        if (entities.isEmpty()) {
            return;
        }
        String haystack = (nullToEmpty(sourceFile.getFunctionalSummary()) + "\n" + readLegacySource(sourceFile))
                .toLowerCase(Locale.ROOT);
        for (GeneratedArtifact entity : entities) {
            String table = entity.getSqlTableName().trim().toLowerCase(Locale.ROOT);
            if (table.isEmpty()) {
                continue;
            }
            Pattern word = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(table) + "(?![a-z0-9_])");
            if (word.matcher(haystack).find()) {
                addMatch(found, sourceFile, entity, REASON_SQL_TABLE);
            }
        }
    }

    private void collectFromSimilarity(MigrationFileStatus sourceFile, int topK,
                                       Map<String, ExistingArtifactMatch> found) {
        String query = buildQuery(sourceFile);
        if (query.isBlank() || topK <= 0) {
            return;
        }
        SearchRequest request = SearchRequest.query(query)
                .withTopK(topK)
                .withSimilarityThreshold(prompts.getConversion().getRegistry().getSimilarityThreshold())
                .withFilterExpression("docType == '" + DOC_TYPE + "'");
        List<Document> documents = vectorStore.similaritySearch(request);
        if (documents == null || documents.isEmpty()) {
            return;
        }
        List<String> fqcns = documents.stream()
                .map(d -> d.getMetadata().get("fqcn"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .distinct()
                .toList();
        Map<String, GeneratedArtifact> byFqcn = new LinkedHashMap<>();
        artifactRepository.findByFqcnIn(fqcns).stream()
                .sorted(Comparator.comparing(GeneratedArtifact::getId))
                .forEach(a -> byFqcn.putIfAbsent(a.getFqcn(), a));
        for (String fqcn : fqcns) {
            addMatch(found, sourceFile, byFqcn.get(fqcn), REASON_SIMILARITY);
        }
    }

    private void addMatch(Map<String, ExistingArtifactMatch> found, MigrationFileStatus sourceFile,
                          GeneratedArtifact artifact, String reason) {
        if (artifact == null || !isRegistrable(artifact)) {
            return;
        }
        if (sourceFile.getId() != null && sourceFile.getId().equals(artifact.getSourceStatusId())) {
            return;
        }
        String fqcn = artifact.getFqcn();
        String signature = artifact.getCompactSignature();
        String table = artifact.getSqlTableName();
        if (fqcn == null || fqcn.isBlank() || signature == null || signature.isBlank()) {
            CompactSignatureExtractor.Extraction extraction = extractor.extract(
                    artifact.getGeneratedCode(), artifact.getArtifactType(), artifact.getRelativeTargetPath());
            fqcn = extraction.fqcn();
            signature = extraction.compactSignature();
            table = table != null ? table : extraction.sqlTableName();
        }
        if (fqcn == null || fqcn.isBlank()) {
            return;
        }
        found.putIfAbsent(fqcn, new ExistingArtifactMatch(fqcn, artifact.getArtifactType().toUpperCase(Locale.ROOT),
                reason, table, signature));
    }

    private List<ExistingArtifactMatch> sortByPriority(Map<String, ExistingArtifactMatch> found) {
        // tri stable : à priorité égale, l'ordre de découverte (déterministe puis pertinence) est conservé
        return found.values().stream()
                .sorted(Comparator.comparingInt(m -> typePriority(m.artifactType())))
                .toList();
    }

    private int typePriority(String type) {
        return "ENTITY".equals(type) ? 0 : "DTO".equals(type) ? 1 : 2;
    }

    private List<ExistingArtifactMatch> applyBudget(List<ExistingArtifactMatch> sorted, int maxBudgetChars) {
        List<ExistingArtifactMatch> kept = new ArrayList<>();
        int used = 0;
        for (ExistingArtifactMatch match : sorted) {
            int size = match.render().length();
            if (used + size <= maxBudgetChars) {
                kept.add(match);
                used += size;
                continue;
            }
            int allowed = maxBudgetChars - used - match.withSignature("").render().length() - "  // ...\n".length();
            String signature = match.compactSignature() == null ? "" : match.compactSignature();
            if (allowed >= MIN_TRUNCATED_SIGNATURE_CHARS && signature.length() > allowed) {
                String cut = signature.substring(0, allowed);
                int lastLine = cut.lastIndexOf('\n');
                if (lastLine > 0) {
                    kept.add(match.withSignature(cut.substring(0, lastLine) + "\n  // ..."));
                }
            }
            break;
        }
        return kept;
    }

    private String buildQuery(MigrationFileStatus sourceFile) {
        String className = sourceFile.getSourceFile() == null ? ""
                : sourceFile.getSourceFile().replaceAll("^.*[\\\\/]", "").replaceAll("\\.[^.]+$", "");
        String query = String.join(" ", nullToEmpty(sourceFile.getFunctionalSummary()), className,
                nullToEmpty(sourceFile.getSuggestedFunctionalLot())).trim();
        return query.length() > MAX_QUERY_CHARS ? query.substring(0, MAX_QUERY_CHARS) : query;
    }

    private String readLegacySource(MigrationFileStatus sourceFile) {
        if (sourceFile.getAbsolutePath() == null) {
            return "";
        }
        try {
            Path path = Paths.get(sourceFile.getAbsolutePath());
            if (!Files.isRegularFile(path)) {
                return "";
            }
            String content = Files.readString(path, StandardCharsets.ISO_8859_1);
            return content.length() > MAX_LEGACY_SOURCE_CHARS ? content.substring(0, MAX_LEGACY_SOURCE_CHARS) : content;
        } catch (Exception ex) {
            return "";
        }
    }

    private boolean isRegistrable(GeneratedArtifact artifact) {
        return artifact.getArtifactType() != null
                && REGISTERED_TYPES.contains(artifact.getArtifactType().trim().toUpperCase(Locale.ROOT));
    }

    private String documentId(String fqcn) {
        return UUID.nameUUIDFromBytes(("target-artifact:" + fqcn).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
