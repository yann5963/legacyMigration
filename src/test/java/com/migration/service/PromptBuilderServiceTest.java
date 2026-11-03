package com.migration.service;

import com.migration.model.ContextMode;
import com.migration.model.ContextReference;
import com.migration.parser.LegacyCodeParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptBuilderServiceTest {

    @Mock
    private LegacyCodeParserService parserService;

    @InjectMocks
    private PromptBuilderService promptBuilderService;

    private Path tempFile;

    @BeforeEach
    void setUp() throws IOException {
        tempFile = Files.createTempFile("test", ".java");
        Files.writeString(tempFile, "public class Test {}");
    }

    @Test
    void resolveContextFiles_ShouldReturnEmptyString_WhenListIsNull() {
        assertThat(promptBuilderService.resolveContextFiles(null)).isEmpty();
    }

    @Test
    void resolveContextFiles_ShouldReturnEmptyString_WhenListIsEmpty() {
        assertThat(promptBuilderService.resolveContextFiles(List.of())).isEmpty();
    }

    @Test
    void resolveContextFiles_ShouldIncludeFullContent_WhenModeIsFull() {
        ContextReference ref = new ContextReference();
        ref.setFilePath(tempFile.toString());
        ref.setMode(ContextMode.FULL);

        String result = promptBuilderService.resolveContextFiles(List.of(ref));

        assertThat(result)
            .contains("### Fichiers de référence pertinents")
            .contains("public class Test {}")
            .contains("Mode: FULL");
    }

    @Test
    void resolveContextFiles_ShouldExtractSignatures_WhenModeIsSignatureOnly() {
        when(parserService.extractSignatures(anyString())).thenReturn("public class Test_Extracted {}");

        ContextReference ref = new ContextReference();
        ref.setFilePath(tempFile.toString());
        ref.setMode(ContextMode.SIGNATURE_ONLY);

        String result = promptBuilderService.resolveContextFiles(List.of(ref));

        assertThat(result)
            .contains("### Fichiers de référence pertinents")
            .contains("public class Test_Extracted {}")
            .contains("Mode: SIGNATURE_ONLY");
    }

    @Test
    void resolveContextFiles_ShouldIgnoreMissingFiles() {
        ContextReference ref = new ContextReference();
        ref.setFilePath("/path/to/missing/file.java");
        ref.setMode(ContextMode.FULL);

        String result = promptBuilderService.resolveContextFiles(List.of(ref));

        assertThat(result)
            .contains("### Fichiers de référence pertinents")
            .doesNotContain("/path/to/missing/file.java");
    }
}
