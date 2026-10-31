package com.migration.service;

import com.migration.model.InitializrRequest;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class SpringInitializrService {

    private final ProjectConfigurationRepository configRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    public void generateProject(InitializrRequest request) throws Exception {
        ProjectConfiguration config = configRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("Configuration introuvable"));

        String targetPath = config.getTargetProjectPath();
        if (targetPath == null || targetPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Le chemin du projet de destination n'est pas configuré.");
        }

        Path path = Paths.get(targetPath);
        File dir = path.toFile();

        // Check if directory exists and is not empty
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                throw new IllegalArgumentException("Le chemin spécifié n'est pas un répertoire valide.");
            }
            try (Stream<Path> entries = Files.list(path)) {
                if (entries.findFirst().isPresent()) {
                    throw new IllegalStateException("Le répertoire de destination n'est pas vide.");
                }
            }
        } else {
            // Create directories if they don't exist
            Files.createDirectories(path);
        }

        // Build start.spring.io URL
        String url = buildInitializrUrl(config, request);

        // Download and Extract
        File tempZip = File.createTempFile("spring-initializr", ".zip");
        try {
            // Download ZIP
            restTemplate.execute(url, HttpMethod.GET, null, clientHttpResponse -> {
                StreamUtils.copy(clientHttpResponse.getBody(), new FileOutputStream(tempZip));
                return tempZip;
            });

            // Extract ZIP
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip.toPath()))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File newFile = new File(dir, entry.getName());
                    if (entry.isDirectory()) {
                        newFile.mkdirs();
                    } else {
                        new File(newFile.getParent()).mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(newFile)) {
                            StreamUtils.copy(zis, fos);
                        }
                    }
                    zis.closeEntry();
                }
            }
        } finally {
            Files.deleteIfExists(tempZip.toPath());
        }
    }

    private String buildInitializrUrl(ProjectConfiguration config, InitializrRequest request) {
        StringBuilder url = new StringBuilder("https://start.spring.io/starter.zip?type=maven-project&language=java&packaging=jar");

        if (config.getTargetGroupId() != null && !config.getTargetGroupId().trim().isEmpty()) {
            url.append("&groupId=").append(config.getTargetGroupId().trim());
        }
        if (config.getTargetArtifactId() != null && !config.getTargetArtifactId().trim().isEmpty()) {
            url.append("&artifactId=").append(config.getTargetArtifactId().trim());
        }
        if (config.getTargetJavaVersion() != null && !config.getTargetJavaVersion().trim().isEmpty()) {
            url.append("&javaVersion=").append(config.getTargetJavaVersion().trim());
        }
        if (config.getTargetSpringBootVersion() != null && !config.getTargetSpringBootVersion().trim().isEmpty()) {
            // Spring Initializr expects exact boot version or allows it to default to latest.
            // We pass it if valid, otherwise fallback.
            String bootVer = config.getTargetSpringBootVersion().trim();
            if (bootVer.matches("^[0-9]+\\.[0-9]+\\.[0-9]+.*$")) {
                 url.append("&bootVersion=").append(bootVer);
            }
        }
        if (request.getDependencies() != null && !request.getDependencies().isEmpty()) {
            url.append("&dependencies=").append(String.join(",", request.getDependencies()));
        }

        return url.toString();
    }
}
