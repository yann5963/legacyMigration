package com.migration.service;

import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.repository.MigrationFileStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class SourceScannerService {

    private final MigrationFileStatusRepository repository;

    public int scanProject(String sourceRootPath) {
        log.info("Démarrage du scan du projet : {}", sourceRootPath);
        Path rootPath = Paths.get(sourceRootPath);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            log.error("Chemin invalide ou introuvable : {}", sourceRootPath);
            throw new IllegalArgumentException("Chemin du projet source invalide.");
        }

        int fileCount = 0;
        try (Stream<Path> paths = Files.walk(rootPath)) {
            fileCount = (int) paths
                    .filter(Files::isRegularFile)
                    .filter(this::isRelevantFile)
                    .map(path -> {
                        String fileName = path.getFileName().toString();
                        String fileType = getFileExtension(fileName);

                        MigrationFileStatus fileStatus = MigrationFileStatus.builder()
                                .sourceFile(fileName)
                                .absolutePath(path.toAbsolutePath().toString())
                                .fileType(fileType)
                                .status(MigrationStatus.SCANNED)
                                .isConverted(false)
                                .isValidated(false)
                                .build();

                        repository.save(fileStatus);
                        return 1;
                    }).count();

        } catch (IOException e) {
            log.error("Erreur lors du scan du répertoire : {}", sourceRootPath, e);
            throw new RuntimeException("Erreur lors du scan", e);
        }

        log.info("Scan terminé. {} fichiers trouvés.", fileCount);
        return fileCount;
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
