package com.migration.ai;

import com.migration.repository.ProjectConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MigrationAiServiceTest {
    @Test
    void preAnalysisIncludesRootFlowsOutsideTruncatedSource() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.call(any(Prompt.class)).getResult().getOutput().getContent()).thenReturn("""
                {"architecturalRole":"BUSINESS_SERVICE","migrationComplexity":"LOW",
                 "functionalSummary":"Service","targetSpringArtifact":"Service",
                 "detectedRisks":[],"suggestedFunctionalLot":"Batch"}
                """);
        clearInvocations(client);
        MigrationAiService service = new MigrationAiService(client, mock(ProjectConfigurationRepository.class));

        service.preAnalyzeFile("Service.java", "JAVA", "a".repeat(9000), "model", "Bootstrap.java, web.xml");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(client).call(prompt.capture());
        String userMessage = prompt.getValue().getInstructions().get(1).getContent();
        assertTrue(userMessage.contains("Contexte de flux : ce composant est sollicité depuis les points d'entrée suivants : Bootstrap.java, web.xml."));
        assertTrue(userMessage.contains("contenu tronqué"));
    }
}
