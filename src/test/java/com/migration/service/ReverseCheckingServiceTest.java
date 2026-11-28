package com.migration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.migration.ai.MigrationAiService;
import com.migration.config.MigrationPromptsProperties;
import com.migration.controller.DashboardController;
import com.migration.dto.ReverseCheckReport;
import com.migration.model.GeneratedArtifact;
import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.model.ParityStatus;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.repository.ProjectConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReverseCheckingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void migrationAiServiceParsesStructuredReverseCheckReportAndNormalizesStatus() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ReverseCheckReport llmReport = new ReverseCheckReport(
                75,
                "CONFORME",
                "Une règle métier est partielle.",
                List.of(new ReverseCheckReport.RuleCheckItem(
                        "Vérification du plafond de remise",
                        "PARTIEL",
                        "OrderService.java",
                        "Le plafond de 15% n'est pas contrôlé")),
                List.of("Clause WHERE status = 'ACTIVE' absente dans OrderRepository"));
        String json = new ObjectMapper().writeValueAsString(llmReport);
        when(client.call(any(Prompt.class)).getResult().getOutput().getContent()).thenReturn(json);

        MigrationAiService aiService = new MigrationAiService(
                client,
                mock(ProjectConfigurationRepository.class),
                new MigrationPromptsProperties());

        ReverseCheckReport result = aiService.reverseCheck(
                "OrderAction.java",
                "public class OrderAction {}",
                "class OrderService {}",
                "qwen2.5-coder:3b");

        assertThat(result.score()).isEqualTo(75);
        assertThat(result.globalStatus()).isEqualTo("ECARTS_DETECTES");
        assertThat(result.rules()).hasSize(1);
        assertThat(result.rules().get(0).status()).isEqualTo("PARTIEL");
        assertThat(result.sqlAndDataChecks()).hasSize(1);
    }

    @Test
    void performReverseCheckPersistsScoreStatusAndJsonReport() throws Exception {
        Path legacyFile = tempDir.resolve("LegacyDao.java");
        Files.writeString(legacyFile, "public class LegacyDao { /* SELECT * FROM users WHERE active = 1 */ }");

        GeneratedArtifact artifact = GeneratedArtifact.builder()
                .relativeTargetPath("src/main/java/com/app/UserRepository.java")
                .artifactType("REPOSITORY")
                .generatedCode("public interface UserRepository {}")
                .build();

        MigrationFileStatus fileStatus = MigrationFileStatus.builder()
                .id(42L)
                .sourceFile("LegacyDao.java")
                .absolutePath(legacyFile.toString())
                .status(MigrationStatus.NEEDS_REVIEW)
                .generatedArtifacts(List.of(artifact))
                .build();

        MigrationFileStatusRepository repository = mock(MigrationFileStatusRepository.class);
        when(repository.findById(42L)).thenReturn(Optional.of(fileStatus));
        when(repository.save(any(MigrationFileStatus.class))).thenAnswer(inv -> inv.getArgument(0));

        MigrationAiService aiService = mock(MigrationAiService.class);
        ReverseCheckReport expected = new ReverseCheckReport(
                100,
                "CONFORME",
                "Parité fonctionnelle complète.",
                List.of(new ReverseCheckReport.RuleCheckItem(
                        "Filtre active = 1",
                        "IMPLEMENTE",
                        "UserRepository.java",
                        "")),
                List.of());
        when(aiService.reverseCheck(eq("LegacyDao.java"), anyString(), anyString(), isNull()))
                .thenReturn(expected);

        ObjectMapper objectMapper = new ObjectMapper();
        ReverseCheckingService service = new ReverseCheckingService(repository, aiService, objectMapper);

        ReverseCheckReport actual = service.performReverseCheck(42L, null);

        assertThat(actual).isEqualTo(expected);
        assertThat(fileStatus.getParityScore()).isEqualTo(100);
        assertThat(fileStatus.getParityStatus()).isEqualTo(ParityStatus.CONFORME);
        assertThat(fileStatus.getParityCheckedAt()).isNotNull();
        assertThat(fileStatus.isParityStale()).isFalse();
        assertThat(fileStatus.getParityReport()).contains("\"globalStatus\":\"CONFORME\"");
        verify(repository).save(fileStatus);
    }

    @Test
    void dashboardControllerGetReverseCheckReturnsSavedOrEmptyWithoutCallingAi() throws Exception {
        ReverseCheckingService reverseCheckingService = mock(ReverseCheckingService.class);
        ReverseCheckReport report = new ReverseCheckReport(
                85,
                "ECARTS_DETECTES",
                "Synthèse sauvegardée",
                List.of(),
                List.of());
        when(reverseCheckingService.getSavedReverseCheck(7L)).thenReturn(Optional.of(
                new ReverseCheckingService.SavedReverseCheck(
                        report, 85, ParityStatus.ECARTS_DETECTES, java.time.LocalDateTime.of(2026, 11, 28, 10, 30), true)));
        when(reverseCheckingService.getSavedReverseCheck(8L)).thenReturn(Optional.empty());

        DashboardController controller = new DashboardController(
                mock(MigrationFileStatusRepository.class),
                mock(AsyncMigrationRetryService.class),
                mock(SemanticPreAnalysisService.class),
                mock(DynamicPromptBuilderService.class),
                mock(DynamicRoutingService.class),
                reverseCheckingService,
                mock(VectorStore.class));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/migration/files/7/reverse-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.parityScore").value(85))
                .andExpect(jsonPath("$.parityStale").value(true))
                .andExpect(jsonPath("$.report.summary").value("Synthèse sauvegardée"));

        mockMvc.perform(get("/api/migration/files/8/reverse-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));

        verify(reverseCheckingService, never()).performReverseCheck(anyLong(), any());
    }

    @Test
    void dashboardControllerReverseCheckEndpointReturnsReportJson() throws Exception {
        ReverseCheckingService reverseCheckingService = mock(ReverseCheckingService.class);
        ReverseCheckReport report = new ReverseCheckReport(
                60,
                "ECARTS_DETECTES",
                "Règle manquante.",
                List.of(new ReverseCheckReport.RuleCheckItem(
                        "Validation email",
                        "MANQUANT",
                        "UserController.java",
                        "Annotation @Email absente")),
                List.of());
        when(reverseCheckingService.performReverseCheck(7L, null)).thenReturn(report);

        DashboardController controller = new DashboardController(
                mock(MigrationFileStatusRepository.class),
                mock(AsyncMigrationRetryService.class),
                mock(SemanticPreAnalysisService.class),
                mock(DynamicPromptBuilderService.class),
                mock(DynamicRoutingService.class),
                reverseCheckingService,
                mock(VectorStore.class));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/migration/files/7/reverse-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(60))
                .andExpect(jsonPath("$.globalStatus").value("ECARTS_DETECTES"))
                .andExpect(jsonPath("$.rules[0].status").value("MANQUANT"));
    }
}
