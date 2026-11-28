package com.migration.ai;

import com.migration.config.MigrationPromptsProperties;
import com.migration.dto.MultiFileConversionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migration.repository.ProjectConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MigrationAiServiceTest {
    @Test
    void retriesNameOnlyGeneratedCodeWithTheOriginalSourceAndValidationError() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        String invalid = conversionJson("src/main/java/com/acme/ErreurDTO.java", "DTO",
                "package com.acme;\nErreurDTO");
        String completeCode = "package com.acme; public record ErreurDTO(String message) {}";
        when(client.call(any(Prompt.class)).getResult().getOutput().getContent())
                .thenReturn(invalid, conversionJson("src/main/java/com/acme/ErreurDTO.java", "DTO", completeCode));
        clearInvocations(client);
        MigrationPromptsProperties prompts = conversionPrompts();
        MigrationAiService service = new MigrationAiService(client, mock(ProjectConfigurationRepository.class), prompts);
        String legacy = "public class Erreur { public String message() { return \"legacy\"; } }";

        var result = service.convertToMultiFile("Convert to Spring Boot", legacy, "coder");

        assertThat(result.artifacts().get(0).code()).isEqualTo(completeCode);
        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(client, times(2)).call(sent.capture());
        var repairInstructions = sent.getAllValues().get(1).getInstructions();
        assertThat(repairInstructions.get(1).getContent()).contains(legacy);
        assertThat(repairInstructions.get(2).getContent()).contains("Erreur de validation",
                "fichier transforme complet", "ErreurDTO.java");
    }

    @Test
    void retriesSyntaxErrorWithLineLocationAndJavaFixInstructionBeforeRawResponse() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        String invalidRecord = "package com.migrated.entity;\n\npublic record StructureXmlRacvision {\n    private String nom;\n}";
        String validClass = "package com.migrated.entity;\n\npublic class StructureXmlRacvision {\n    private String nom;\n}";
        when(client.call(any(Prompt.class)).getResult().getOutput().getContent())
                .thenReturn(
                        conversionJson("src/main/java/com/migrated/entity/StructureXmlRacvision.java", "ENTITY", invalidRecord),
                        conversionJson("src/main/java/com/migrated/entity/StructureXmlRacvision.java", "ENTITY", validClass));
        clearInvocations(client);
        MigrationAiService service = new MigrationAiService(client, mock(ProjectConfigurationRepository.class),
                conversionPrompts());

        var result = service.convertToMultiFile("Convert to Spring Boot", "public class StructureXmlRacvision {}", "coder");

        assertThat(result.artifacts().get(0).code()).isEqualTo(validClass);
        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(client, times(2)).call(sent.capture());
        String repairContent = sent.getAllValues().get(1).getInstructions().get(2).getContent();
        assertThat(repairContent)
                .contains("Ligne 3, col")
                .contains("public record StructureXmlRacvision {")
                .contains("public record Nom(...)");
        assertThat(repairContent.indexOf("Erreur de validation :"))
                .isLessThan(repairContent.indexOf("Corrige cette reponse :"));
    }

    @Test
    void failsExplicitlyIfRepairStillContainsNoJavaType() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        String invalid = conversionJson("src/main/java/com/acme/ErreurDTO.java", "DTO", "package com.acme;");
        when(client.call(any(Prompt.class)).getResult().getOutput().getContent()).thenReturn(invalid);
        clearInvocations(client);
        MigrationAiService service = new MigrationAiService(client, mock(ProjectConfigurationRepository.class),
                conversionPrompts());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.convertToMultiFile("Convert", "public class Erreur {}", "coder"));

        assertThat(failure.getMessage()).contains("Aucune declaration de type Java", "ErreurDTO.java");
        verify(client, times(2)).call(any(Prompt.class));
    }

    @Test
    void preservesValidNonJavaArtifactsWithoutAttemptingAJavaRepair() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.call(any(Prompt.class)).getResult().getOutput().getContent())
                .thenReturn(conversionJson("src/main/resources/templates/error.html", "VIEW",
                                "<html><body th:text=\"${message}\"></body></html>"),
                        conversionJson("src/main/resources/application.yml", "CONFIG", "server:\n  port: 8081\n"));
        clearInvocations(client);
        MigrationAiService service = new MigrationAiService(client, mock(ProjectConfigurationRepository.class),
                conversionPrompts());

        var result = service.convertToMultiFile("Convert JSP", "<html>legacy</html>", "coder");

        assertThat(result.artifacts().get(0).code()).contains("th:text");
        var yaml = service.convertToMultiFile("Convert XML", "<server/>", "coder");
        assertThat(yaml.artifacts().get(0).code()).contains("port: 8081");
        verify(client, times(2)).call(any(Prompt.class));
    }

    private MigrationPromptsProperties conversionPrompts() {
        var prompts = new MigrationPromptsProperties();
        prompts.getConversion().setUser("Convertis le code source suivant : {sourceCode}");
        prompts.getConversion().setResponseFormat("Reponds en JSON : {format}");
        prompts.getConversion().setRepair("Corrige cette reponse : {rawResponse}");
        return prompts;
    }

    private String conversionJson(String path, String type, String code) throws Exception {
        return new ObjectMapper().writeValueAsString(new MultiFileConversionResponse("Conversion",
                java.util.List.of(new MultiFileConversionResponse.TargetFileItem(path, type, code))));
    }

    @Test
    void springCreatesServiceWithConfiguredPrompts() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.call(any(Prompt.class)).getResult().getOutput().getContent()).thenReturn("Clarification");
        clearInvocations(client);
        MigrationPromptsProperties prompts = new MigrationPromptsProperties();
        prompts.setClarification("Configured prompt: {context} / {ambiguity}");

        new ApplicationContextRunner()
                .withBean(ChatClient.class, () -> client)
                .withBean(ProjectConfigurationRepository.class, () -> mock(ProjectConfigurationRepository.class))
                .withBean(MigrationPromptsProperties.class, () -> prompts)
                .withUserConfiguration(MigrationAiService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MigrationAiService.class);
                    String response = context.getBean(MigrationAiService.class)
                            .askForClarification("legacy context", "uncertain mapping");

                    assertThat(response).isEqualTo("Clarification");
                    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
                    verify(client).call(prompt.capture());
                    assertThat(prompt.getValue().getInstructions().get(0).getContent())
                            .isEqualTo("Configured prompt: legacy context / uncertain mapping");
                });
    }

    @Test
    void preAnalysisIncludesRootFlowsOutsideTruncatedSource() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.call(any(Prompt.class)).getResult().getOutput().getContent()).thenReturn("""
                {"architecturalRole":"BUSINESS_SERVICE","migrationComplexity":"LOW",
                 "functionalSummary":"Service","targetSpringArtifact":"Service",
                 "detectedRisks":[],"suggestedFunctionalLot":"Batch"}
                """);
        clearInvocations(client);
        MigrationPromptsProperties prompts = new MigrationPromptsProperties();
        prompts.getPreAnalysis().setSystem("Analyse le fichier et reponds selon ce schema : {format}");
        prompts.getPreAnalysis().setUser("Fichier : {fileName}\nCode : {content}");
        prompts.getPreAnalysis().setRootContext("Contexte de flux : ce composant est sollicité depuis les points d'entrée suivants : {rootEntryPoints}.");
        MigrationAiService service = new MigrationAiService(client, mock(ProjectConfigurationRepository.class), prompts);

        service.preAnalyzeFile("Service.java", "JAVA", "a".repeat(9000), "model", "Bootstrap.java, web.xml");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(client).call(prompt.capture());
        String userMessage = prompt.getValue().getInstructions().get(1).getContent();
        assertTrue(userMessage.contains("Contexte de flux : ce composant est sollicité depuis les points d'entrée suivants : Bootstrap.java, web.xml."));
        assertTrue(userMessage.contains("contenu tronqué"));
    }
}
