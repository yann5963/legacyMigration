package com.migration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.migration.ai.MigrationAiService;
import com.migration.config.MigrationPromptsProperties;
import com.migration.model.GeneratedArtifact;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.GeneratedArtifactRepository;
import com.migration.repository.ProjectConfigurationRepository;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.parser.CompactSignatureExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectFinalizationServiceTest {
    @TempDir Path root;
    private final GeneratedArtifactRepository artifacts = mock(GeneratedArtifactRepository.class);
    private final ProjectConfigurationRepository configurations = mock(ProjectConfigurationRepository.class);
    private final TargetArtifactRegistryService registry = mock(TargetArtifactRegistryService.class);
    private final MigrationAiService ai = mock(MigrationAiService.class);
    private final FinalizationMavenRunner maven = mock(FinalizationMavenRunner.class);
    private final List<GeneratedArtifact> files = new ArrayList<>();
    private final MigrationPromptsProperties prompts = new MigrationPromptsProperties();
    private ProjectConfiguration config;
    private ProjectFinalizationService service;

    @BeforeEach
    void setup() {
        config = ProjectConfiguration.builder().id(1L).targetProjectPath(root.toString())
                .targetGroupId("com.acme").targetArtifactId("sample").build();
        when(configurations.findById(1L)).thenReturn(Optional.of(config));
        when(artifacts.findAll()).thenAnswer(invocation -> List.copyOf(files));
        when(artifacts.findById(anyLong())).thenAnswer(invocation -> files.stream()
                .filter(f -> f.getId().equals(invocation.getArgument(0))).findFirst());
        when(registry.findReferencedSignatures(anyString(), anyLong())).thenReturn("class Dependency { int id(); }");
        prompts.getFinalization().setSystem("Surgical JSON patch only.");
        prompts.getFinalization().setUserTemplate("{fileName}\n{compilerErrors}\n{codeSlices}\n{referencedSignatures}");
        service = createService(maven);
    }

    private ProjectFinalizationService createService(FinalizationMavenRunner runner) {
        return new ProjectFinalizationService(artifacts, configurations, registry, ai, prompts,
                new ObjectMapper(), new FinalizationCodeSupport(), runner, new TargetProjectPreparationService());
    }

    @Test
    void preparesAllArtifactsWithoutAiAndPreservesExistingConfiguration() throws Exception {
        GeneratedArtifact artifact = add(1, "SERVICE", "Example", """
                package legacy;
                import javax.servlet.http.HttpServletRequest;
                import org.apache.struts.action.Action;
                public class Example { public int value() { return 1; } }
                """);
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("src/main/resources/application.yml"), "server:\n  port: 9099\n");
        var summary = service.prepareTargetProject();
        assertEquals("PREPARED", artifact.getCompilationStatus());
        assertNotNull(artifact.getLastProcessedAt());
        assertNull(summary.buildSuccess());
        assertTrue(Files.readString(root.resolve(artifact.getRelativeTargetPath())).contains("package com.acme;"));
        assertFalse(artifact.getGeneratedCode().contains("javax.servlet"));
        assertTrue(Files.exists(root.resolve("src/main/java/com/acme/MigrationApplication.java")));
        assertTrue(Files.readString(root.resolve("pom.xml")).contains("<java.version>21</java.version>"));
        assertTrue(Files.readString(root.resolve("src/main/resources/application.yml")).contains("9099"));
        String pom = Files.readString(root.resolve("pom.xml"));
        service.prepareTargetProject();
        assertEquals(pom, Files.readString(root.resolve("pom.xml")));
        verifyNoInteractions(ai);
    }

    @Test
    void mapsErrorsDeduplicatesAndOrdersBottomUpWithoutTreatingInfraFailureAsSuccess() throws Exception {
        GeneratedArtifact controller = add(1, "CONTROLLER", "Web", "package com.acme; public class Web {}");
        GeneratedArtifact entity = add(2, "ENTITY", "Order", "package com.acme; public class Order {}");
        service.prepareTargetProject();
        String error = error(entity, "cannot find symbol");
        when(maven.build(root)).thenReturn(new FinalizationMavenRunner.BuildOutput(false, error + error));
        var summary = service.runMavenBuildAndAnalyzeErrors();
        assertFalse(summary.buildSuccess());
        assertEquals(1, summary.totalErrors());
        assertEquals(1, summary.filesInErrorCount());
        assertEquals(entity.getId(), summary.files().get(0).id());
        assertEquals("COMPILATION_ERROR", entity.getCompilationStatus());
        assertEquals("COMPILING_OK", controller.getCompilationStatus());
        assertNotNull(config.getLastBuildAt());
        when(maven.build(root)).thenReturn(new FinalizationMavenRunner.BuildOutput(false,
                "[ERROR] Could not resolve dependencies\n"));
        service.runMavenBuildAndAnalyzeErrors();
        assertEquals("PENDING", controller.getCompilationStatus());
        assertEquals("PENDING", entity.getCompilationStatus());
        assertTrue(config.getLastBuildDiagnostic().contains("Could not resolve"));
    }

    @Test
    void manualFixUsesEditedPromptAndModelPersistsAttemptThenRebuilds() throws Exception {
        GeneratedArtifact artifact = add(1, "SERVICE", "Example",
                "package com.acme; public class Example { public int value() { return missing; } }");
        service.prepareTargetProject();
        when(maven.build(root)).thenReturn(new FinalizationMavenRunner.BuildOutput(false, error(artifact, "cannot find symbol")))
                .thenReturn(new FinalizationMavenRunner.BuildOutput(true, "BUILD SUCCESS"));
        service.runMavenBuildAndAnalyzeErrors();
        String preview = service.getFixPrompt(1L).prompt();
        assertTrue(preview.contains("return missing;"));
        assertTrue(preview.contains("class Dependency"));
        assertFalse(preview.contains("BUILD SUCCESS"));
        when(ai.analyzeAndRefactor(anyString(), anyMap(), eq("edited prompt"), isNull(), eq("coder")))
                .thenReturn("{\"replacements\":[{\"original\":\"return missing;\",\"replacement\":\"return 42;\"}]}");
        var result = service.fixSingleArtifact(1L, "edited prompt", "coder");
        assertTrue(result.applied());
        assertTrue(result.build().buildSuccess());
        assertEquals(1, artifact.getFixAttemptsCount());
        assertEquals("edited prompt", artifact.getLastUsedFixPrompt());
        assertTrue(Files.readString(root.resolve(artifact.getRelativeTargetPath())).contains("return 42;"));
        assertEquals("COMPILING_OK", artifact.getCompilationStatus());
        verify(maven, times(2)).build(root);
    }

    @Test
    void realChatBoundaryPreservesJsonSystemInstructionsAndNeverSendsFullBuildLog() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.call(any(Prompt.class)).getResult().getOutput().getContent())
                .thenReturn("{\"replacements\":[{\"original\":\"return missing;\",\"replacement\":\"return 42;\"}]}");
        clearInvocations(client);
        prompts.getFinalization().setSystem("Return JSON: {\"replacements\":[{\"original\":\"...\",\"replacement\":\"...\"}]}");
        var actualRegistry = new TargetArtifactRegistryService(artifacts, mock(MigrationFileStatusRepository.class),
                mock(VectorStore.class), new CompactSignatureExtractor(), prompts);
        service = new ProjectFinalizationService(artifacts, configurations, actualRegistry,
                new MigrationAiService(client, configurations, prompts), prompts, new ObjectMapper(),
                new FinalizationCodeSupport(), maven, new TargetProjectPreparationService());
        var artifact = add(1, "SERVICE", "Example",
                "package com.acme; public class Example { public int value() { return missing; } }");
        add(2, "DTO", "Dependency",
                "package com.acme; public class Dependency { public int id() { return 1234; } }");
        service.prepareTargetProject();
        when(maven.build(root)).thenReturn(new FinalizationMavenRunner.BuildOutput(false,
                        "[INFO] FULL_MAVEN_LOG_MUST_NOT_LEAK\n"
                                + error(artifact, "cannot find symbol") + "  symbol: class Dependency\n"))
                .thenReturn(new FinalizationMavenRunner.BuildOutput(true, "BUILD SUCCESS"));
        service.runMavenBuildAndAnalyzeErrors();
        assertTrue(service.fixSingleArtifact(1L, null, "coder").applied());
        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(client).call(sent.capture());
        assertEquals(prompts.getFinalization().getSystem(), sent.getValue().getInstructions().get(0).getContent());
        String context = sent.getValue().getInstructions().get(1).getContent();
        assertTrue(context.contains("cannot find symbol"));
        assertTrue(context.contains("return missing;"));
        assertTrue(context.contains("public int id();"));
        assertFalse(context.contains("return 1234;"));
        assertFalse(context.contains("FULL_MAVEN_LOG_MUST_NOT_LEAK"));
    }

    @Test
    void rejectedMethodDeletionNeverWritesAndIsExplicitlyReported() throws Exception {
        GeneratedArtifact artifact = add(1, "SERVICE", "Example",
                "package com.acme; public class Example { public int value() { return missing; } }");
        service.prepareTargetProject();
        when(maven.build(root)).thenReturn(new FinalizationMavenRunner.BuildOutput(false, error(artifact, "cannot find symbol")));
        service.runMavenBuildAndAnalyzeErrors();
        String before = artifact.getGeneratedCode();
        String method = before.substring(before.indexOf("public int value"), before.lastIndexOf('}')).stripTrailing();
        String response = new ObjectMapper().writeValueAsString(new FinalizationCodeSupport.FixPatch(
                List.of(new FinalizationCodeSupport.Replacement(method, ""))));
        when(ai.analyzeAndRefactor(anyString(), anyMap(), anyString(), isNull(), isNull())).thenReturn(response);
        var result = service.fixSingleArtifact(1L, null, null);
        assertFalse(result.applied());
        assertTrue(result.message().contains("interdit"));
        assertEquals(before, Files.readString(root.resolve(artifact.getRelativeTargetPath())));
        assertEquals(before, artifact.getGeneratedCode());
        assertEquals("NEEDS_MANUAL_REVIEW", artifact.getCompilationStatus());
        assertEquals(1, artifact.getFixAttemptsCount());
        assertNotNull(artifact.getLastUsedFixPrompt());
    }

    @Test
    void autoFixHonorsTwoAttemptsPerFileAndGlobalBoundThenAllowsManualRetry() throws Exception {
        GeneratedArtifact entity = add(1, "ENTITY", "Order",
                "package com.acme; public class Order { public int value() { return missing; } }");
        service.prepareTargetProject();
        when(maven.build(root)).thenReturn(new FinalizationMavenRunner.BuildOutput(false, error(entity, "missing dependency")));
        when(ai.analyzeAndRefactor(anyString(), anyMap(), anyString(), isNull(), isNull())).thenAnswer(invocation -> {
            String code = entity.getGeneratedCode();
            return code.contains("return missing;")
                    ? "{\"replacements\":[{\"original\":\"return missing;\",\"replacement\":\"return other;\"}]}"
                    : "{\"replacements\":[{\"original\":\"return other;\",\"replacement\":\"return missing;\"}]}";
        });
        service.runStepByStepAutoFix(1);
        assertEquals(1, entity.getFixAttemptsCount());
        service.runStepByStepAutoFix(50);
        assertEquals(2, entity.getFixAttemptsCount());
        assertEquals("NEEDS_MANUAL_REVIEW", entity.getCompilationStatus());
        verify(ai, times(2)).analyzeAndRefactor(anyString(), anyMap(), anyString(), isNull(), isNull());
        service.fixSingleArtifact(1L, null, null);
        assertEquals(3, entity.getFixAttemptsCount());
        verify(maven, times(5)).build(root);
    }

    @Test
    void rejectsTraversalAndDiskChangesBeforeAnyLlmCall() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ProjectFinalizationService.safeResolve(root, "../escape.java"));
        GeneratedArtifact artifact = add(1, "SERVICE", "Example", "package com.acme; public class Example {}");
        service.prepareTargetProject();
        artifact.setLastCompilerErrors(error(artifact, "bad"));
        Files.writeString(root.resolve(artifact.getRelativeTargetPath()), "manually edited");
        assertThrows(IllegalStateException.class, () -> service.fixSingleArtifact(1L, "prompt", null));
        verifyNoInteractions(ai);
    }

    @Test
    void augmentsExistingPomWithInferredDependenciesWithoutReplacingUserSettings() throws Exception {
        add(1, "ENTITY", "Order", """
                package com.acme;
                import jakarta.persistence.Entity;
                import jakarta.validation.constraints.NotNull;
                import lombok.Getter;
                @Entity @Getter public class Order { @NotNull private String number; }
                """);
        add(2, "CONTROLLER", "Web", "package com.acme; public class Web {}");
        service.prepareTargetProject();
        String pom = Files.readString(root.resolve("pom.xml"));
        assertTrue(pom.contains("spring-boot-starter-data-jpa"));
        assertTrue(pom.contains("spring-boot-starter-web"));
        assertTrue(pom.contains("spring-boot-starter-validation"));
        assertTrue(pom.contains("lombok"));
        assertTrue(pom.contains("postgresql"));
        Files.writeString(root.resolve("pom.xml"), pom.replace("<java.version>21</java.version>",
                "<java.version>21</java.version><custom.setting>keep</custom.setting>"));
        service.prepareTargetProject();
        assertTrue(Files.readString(root.resolve("pom.xml")).contains("<custom.setting>keep</custom.setting>"));
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void preparedTargetActuallyCompilesUsingMaven() throws Exception {
        add(1, "DTO", "Value", """
                package old;
                public record Value(String text) {
                    public String unwrap(Object input) {
                        return switch(input) { case String value -> value; default -> text; };
                    }
                }
                """);
        service = createService(new FinalizationMavenRunner());
        service.prepareTargetProject();
        var summary = service.runMavenBuildAndAnalyzeErrors();
        assertTrue(summary.buildSuccess(), summary.diagnostic());
        assertEquals(1, summary.compiledFilesCount());
        assertEquals(0, summary.totalErrors());
    }

    private GeneratedArtifact add(long id, String type, String name, String code) {
        var artifact = GeneratedArtifact.builder().id(id).artifactType(type)
                .relativeTargetPath("src/main/java/com/acme/" + name + ".java").generatedCode(code).build();
        files.add(artifact);
        return artifact;
    }

    private String error(GeneratedArtifact artifact, String message) {
        return "[ERROR] " + root.resolve(artifact.getRelativeTargetPath()).toString().replace('\\', '/')
                + ":[5,9] " + message + "\n";
    }
}
