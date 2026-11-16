package com.migration.service;

import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.parser.LegacyCodeParserService;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Service pour scanner le projet source et construire un graphe de dépendances des fichiers Java.
 * SourceScannerService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceScannerService {

    private final MigrationFileStatusRepository repository;
    private final LegacyCodeParserService parserService;

    public int scanProject(String sourceRootPath) {
        log.info("Démarrage du scan du projet : {}", sourceRootPath);
        Path rootPath = Paths.get(sourceRootPath);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            log.error("Chemin invalide ou introuvable : {}", sourceRootPath);
            throw new IllegalArgumentException("Chemin du projet source invalide.");
        }

        List<Path> relevantPaths = new ArrayList<>();
        try (var paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isRelevantFile)
                 .forEach(relevantPaths::add);
        } catch (IOException e) {
            log.error("Erreur lors du scan du répertoire : {}", sourceRootPath, e);
            throw new RuntimeException("Erreur lors du scan", e);
        }

        Map<String, MigrationFileStatus> fileStatusMap = new HashMap<>();
        Map<String, List<String>> dependencyGraph = new HashMap<>();
        Map<String, String> fqcnToPath = new HashMap<>();

        for (Path path : relevantPaths) {
            String fileName = path.getFileName().toString();
            String fileType = getFileExtension(fileName);
            String absPath = path.toAbsolutePath().toString();

            MigrationFileStatus status = MigrationFileStatus.builder()
                    .sourceFile(fileName)
                    .absolutePath(absPath)
                    .fileType(fileType)
                    .status(MigrationStatus.SCANNED)
                    .isConverted(false)
                    .isValidated(false)
                    .layer(1) // Default layer
                    .build();
            fileStatusMap.put(absPath, status);
            dependencyGraph.put(absPath, new ArrayList<>());

            if (fileType.equals("JAVA")) {
                try {
                    CompilationUnit cu = parserService.parseJavaFile(absPath);
                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cid -> {
                        cid.getFullyQualifiedName().ifPresent(fqcn -> fqcnToPath.put(fqcn, absPath));
                    });
                } catch (Exception e) {
                    log.warn("Impossible de parser le fichier : {}", absPath, e);
                }
            }
        }

        // Build dependency graph based on imports and intra-package usages
        for (Path path : relevantPaths) {
            String absPath = path.toAbsolutePath().toString();
            if (getFileExtension(path.getFileName().toString()).equals("JAVA")) {
                try {
                    CompilationUnit cu = parserService.parseJavaFile(absPath);
                    MigrationFileStatus sourceStatus = fileStatusMap.get(absPath);

                    String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                    if (!packageName.isEmpty()) {
                        cu.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).forEach(t -> {
                            String fqcn = packageName + "." + t.getNameAsString();
                            if (fqcnToPath.containsKey(fqcn)) {
                                String targetPath = fqcnToPath.get(fqcn);
                                if (!absPath.equals(targetPath)) {
                                    if (!dependencyGraph.get(absPath).contains(targetPath)) {
                                        dependencyGraph.get(absPath).add(targetPath);
                                        com.migration.model.ContextReference ref = new com.migration.model.ContextReference();
                                        ref.setFilePath(targetPath);
                                        ref.setMode(com.migration.model.ContextMode.SIGNATURE_ONLY);
                                        sourceStatus.getContextFiles().add(ref);
                                    }
                                }
                            }
                        });
                    }

                    cu.getImports().forEach(imp -> {
                        String importedType = imp.getNameAsString();
                        if (fqcnToPath.containsKey(importedType)) {
                            String targetPath = fqcnToPath.get(importedType);
                            if (!absPath.equals(targetPath)) {
                                if (!dependencyGraph.get(absPath).contains(targetPath)) {
                                    dependencyGraph.get(absPath).add(targetPath);
                                    com.migration.model.ContextReference ref = new com.migration.model.ContextReference();
                                    ref.setFilePath(targetPath);
                                    ref.setMode(com.migration.model.ContextMode.SIGNATURE_ONLY);
                                    sourceStatus.getContextFiles().add(ref);
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    // Ignored, handled in first pass
                }
            }
        }

        // Calculate layers (Topological sort approximation)
        boolean changed;
        int maxIterations = 100;
        do {
            changed = false;
            for (Map.Entry<String, List<String>> entry : dependencyGraph.entrySet()) {
                String sourcePath = entry.getKey();
                MigrationFileStatus sourceStatus = fileStatusMap.get(sourcePath);
                int maxDepLayer = 0;
                for (String depPath : entry.getValue()) {
                    MigrationFileStatus depStatus = fileStatusMap.get(depPath);
                    if (depStatus != null && depStatus.getLayer() > maxDepLayer) {
                        maxDepLayer = depStatus.getLayer();
                    }
                }
                if (sourceStatus.getLayer() <= maxDepLayer) {
                    sourceStatus.setLayer(maxDepLayer + 1);
                    changed = true;
                }
            }
            maxIterations--;
        } while (changed && maxIterations > 0);

        repository.saveAll(fileStatusMap.values());

        log.info("Scan terminé. {} fichiers trouvés.", fileStatusMap.size());
        return fileStatusMap.size();
    }

    private boolean isRelevantFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".java") ||
               fileName.endsWith(".xml") ||
               fileName.endsWith(".jsp") ||
               fileName.endsWith(".properties");
    }

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1).toUpperCase();
        }
        return "UNKNOWN";
    }
}
