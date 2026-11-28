package com.migration.service;

import com.migration.ai.MigrationAiService;
import com.migration.dto.MultiFileConversionResponse;
import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.model.GeneratedArtifact;
import com.migration.parser.LegacyCodeParserService;
import com.migration.repository.MigrationFileStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiFileConversionTest {

    @Mock
    private MigrationFileStatusRepository repository;
    @Mock
    private MigrationAiService aiService;
    @Mock
    private VectorStore vectorStore;
    @Mock
    private LegacyCodeParserService parserService;
    @Mock
    private DynamicPromptBuilderService dynamicPromptBuilderService;

    @InjectMocks
    private AsyncMigrationRetryService retryService;

    @TempDir
    Path tempDir;

    @Test
    void incompleteGeneratedCodeDoesNotReplacePreviousArtifactsOrWriteAPartialBatch() throws Exception {
        ReflectionTestUtils.setField(retryService, "outputDir", tempDir.toString());
        Path sourceFile = tempDir.resolve("Erreur.java");
        Files.writeString(sourceFile, "public class Erreur {}");
        Path previousFile = tempDir.resolve("src/main/java/Previous.java");
        Files.createDirectories(previousFile.getParent());
        Files.writeString(previousFile, "public class Previous { public int value() { return 7; } }");
        var previous = GeneratedArtifact.builder().relativeTargetPath("src/main/java/Previous.java")
                .artifactType("SERVICE").generatedCode(Files.readString(previousFile)).build();
        var status = MigrationFileStatus.builder().id(42L).sourceFile("Erreur.java")
                .absolutePath(sourceFile.toString()).status(MigrationStatus.NEEDS_REVIEW)
                .generatedArtifacts(new ArrayList<>(List.of(previous))).contextFiles(new ArrayList<>()).build();
        when(repository.findById(42L)).thenReturn(Optional.of(status));
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        when(aiService.convertToMultiFile(anyString(), anyString(), isNull())).thenReturn(
                new MultiFileConversionResponse("Incomplete", List.of(
                        new MultiFileConversionResponse.TargetFileItem("src/main/java/NewService.java", "SERVICE",
                                "public class NewService {}"),
                        new MultiFileConversionResponse.TargetFileItem("src/main/java/ErreurDTO.java", "DTO", "ErreurDTO"))));

        retryService.convertFile(42L, "Preserve business logic", null, null);

        assertEquals(MigrationStatus.ERROR, status.getStatus());
        assertTrue(status.getErrorMessage().contains("fichier transforme complet"));
        assertEquals(List.of(previous), status.getGeneratedArtifacts());
        assertEquals(previous.getGeneratedCode(), Files.readString(previousFile));
        assertFalse(Files.exists(tempDir.resolve("src/main/java/NewService.java")));
    }

    @Test
    void convertFile_savesMultipleGeneratedArtifactsAndUpdatesStatus() throws Exception {
        ReflectionTestUtils.setField(retryService, "outputDir", tempDir.toString());

        Path sourceFile = tempDir.resolve("AnneeScolAction.java");
        Files.writeString(sourceFile, "public class AnneeScolAction {}");

        MigrationFileStatus status = MigrationFileStatus.builder()
                .id(42L)
                .sourceFile("AnneeScolAction.java")
                .absolutePath(sourceFile.toString())
                .status(MigrationStatus.ANALYZED)
                .generatedArtifacts(new ArrayList<>())
                .contextFiles(new ArrayList<>())
                .build();

        when(repository.findById(42L)).thenReturn(Optional.of(status));
        when(dynamicPromptBuilderService.buildConversionPrompt(any(), any())).thenReturn("DYNAMIC_PROMPT");
        when(parserService.shouldChunk(anyString())).thenReturn(false);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());

        MultiFileConversionResponse aiResponse = new MultiFileConversionResponse(
                "Séparation Controller / Service / DTO",
                List.of(
                        new MultiFileConversionResponse.TargetFileItem(
                                "src/main/java/com/migration/target/controller/AnneeScolController.java",
                                "CONTROLLER",
                                "public class AnneeScolController {}"
                        ),
                        new MultiFileConversionResponse.TargetFileItem(
                                "src/main/java/com/migration/target/service/AnneeScolService.java",
                                "SERVICE",
                                "public class AnneeScolService {}"
                        ),
                        new MultiFileConversionResponse.TargetFileItem(
                                "src/main/java/com/migration/target/dto/AnneeScolDto.java",
                                "DTO",
                                "public record AnneeScolDto(Long id) {}"
                        )
                )
        );
        when(aiService.convertToMultiFile(anyString(), anyString(), isNull())).thenReturn(aiResponse);

        retryService.convertFile(42L, null, null, null);

        assertEquals(MigrationStatus.NEEDS_REVIEW, status.getStatus());
        assertTrue(status.isConverted());
        assertEquals("DYNAMIC_PROMPT", status.getLastCustomPrompt());
        assertEquals(3, status.getGeneratedArtifacts().size());
        assertEquals(3, status.getTargetFiles().size());
        assertEquals("CONTROLLER", status.getGeneratedArtifacts().get(0).getArtifactType());
        assertTrue(Files.exists(tempDir.resolve("src/main/java/com/migration/target/service/AnneeScolService.java")));
        assertTrue(Files.exists(tempDir.resolve("src/main/java/com/migration/target/dto/AnneeScolDto.java")));
    }
}
