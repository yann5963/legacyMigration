package com.migration.service;

import com.migration.ai.MigrationAiService;
import com.migration.dto.FilePreAnalysisResult;
import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.parser.LegacyCodeParserService;
import com.migration.repository.MigrationFileStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticPreAnalysisServiceTest {

    @Mock
    private MigrationFileStatusRepository repository;

    @Mock
    private MigrationAiService aiService;

    private LegacyCodeParserService parserService;
    private SemanticPreAnalysisService preAnalysisService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parserService = new LegacyCodeParserService();
        preAnalysisService = new SemanticPreAnalysisService(repository, parserService, aiService);
    }

    @Test
    void testTrivialDto_IsDetectedByJavaParserWithoutCallingLlm() throws Exception {
        String dtoCode = """
                package com.legacy.billing.dto;

                public class InvoiceDto {
                    public static final String CURRENCY = "EUR";
                    private Long id;
                    private String customerName;
                    private boolean paid;

                    public InvoiceDto() {}

                    public InvoiceDto(Long id, String customerName) {
                        this.id = id;
                        this.customerName = customerName;
                    }

                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getCustomerName() { return customerName; }
                    public void setCustomerName(String customerName) { this.customerName = customerName; }
                    public boolean isPaid() { return paid; }
                    public void setPaid(boolean paid) { this.paid = paid; }

                    @Override
                    public String toString() { return "InvoiceDto{id=" + id + "}"; }
                }
                """;

        Path dtoFile = tempDir.resolve("InvoiceDto.java");
        Files.writeString(dtoFile, dtoCode);

        MigrationFileStatus status = MigrationFileStatus.builder()
                .id(1L)
                .sourceFile("InvoiceDto.java")
                .absolutePath(dtoFile.toAbsolutePath().toString())
                .fileType("JAVA")
                .status(MigrationStatus.SCANNED)
                .build();

        when(repository.findByStatusIn(anyCollection())).thenReturn(List.of(status));

        preAnalysisService.analyzeAllScannedFiles(null);

        verifyNoInteractions(aiService);
        assertEquals(MigrationStatus.ANALYZED, status.getStatus());
        assertEquals("TRIVIAL", status.getArchitecturalRole());
        assertEquals("TRIVIAL", status.getMigrationComplexity());
        assertEquals("Entity/DTO", status.getTargetSpringArtifact());
        assertNotNull(status.getFunctionalSummary());
        assertEquals("dto", status.getSuggestedFunctionalLot());
    }

    @Test
    void testNonTrivialBusinessService_CallsLlmAndEnrichesMetadata() throws Exception {
        String serviceCode = """
                package com.legacy.billing.service;

                import java.sql.Connection;
                import java.sql.Statement;

                public class BillingService {
                    public double computeTotal(double amount, boolean vip) {
                        if (vip) {
                            return amount * 0.9;
                        }
                        return amount;
                    }
                }
                """;

        Path serviceFile = tempDir.resolve("BillingService.java");
        Files.writeString(serviceFile, serviceCode);

        MigrationFileStatus status = MigrationFileStatus.builder()
                .id(2L)
                .sourceFile("BillingService.java")
                .absolutePath(serviceFile.toAbsolutePath().toString())
                .fileType("JAVA")
                .status(MigrationStatus.SCANNED)
                .build();

        FilePreAnalysisResult llmResult = new FilePreAnalysisResult(
                "BUSINESS_SERVICE",
                "MEDIUM",
                "Calcule le montant total de facturation avec remise VIP.",
                "Service",
                List.of("Import JDBC inutilisé", "Règle de remise codée en dur"),
                "Facturation"
        );

        when(repository.findByStatusIn(anyCollection())).thenReturn(List.of(status));
        when(aiService.preAnalyzeFile(eq("BillingService.java"), eq("JAVA"), anyString(), isNull()))
                .thenReturn(llmResult);

        preAnalysisService.analyzeAllScannedFiles(null);

        verify(aiService, times(1)).preAnalyzeFile(eq("BillingService.java"), eq("JAVA"), anyString(), isNull());
        assertEquals(MigrationStatus.ANALYZED, status.getStatus());
        assertEquals("BUSINESS_SERVICE", status.getArchitecturalRole());
        assertEquals("MEDIUM", status.getMigrationComplexity());
        assertEquals("Service", status.getTargetSpringArtifact());
        assertEquals("Facturation", status.getSuggestedFunctionalLot());
        assertTrue(status.getDetectedRisks().contains("Import JDBC inutilisé"));
    }

    @Test
    void testResilience_LlmFailureOnOneFileDoesNotAbortBatch() throws Exception {
        Path failingFile = tempDir.resolve("LegacyAction.java");
        Files.writeString(failingFile, "public class LegacyAction extends Action { public void execute() { System.out.println(\"hi\"); } }");

        Path healthyFile = tempDir.resolve("Constants.java");
        Files.writeString(healthyFile, "public class Constants { public static final int MAX = 10; }");

        MigrationFileStatus failingStatus = MigrationFileStatus.builder()
                .id(10L)
                .sourceFile("LegacyAction.java")
                .absolutePath(failingFile.toAbsolutePath().toString())
                .fileType("JAVA")
                .status(MigrationStatus.SCANNED)
                .build();

        MigrationFileStatus healthyStatus = MigrationFileStatus.builder()
                .id(11L)
                .sourceFile("Constants.java")
                .absolutePath(healthyFile.toAbsolutePath().toString())
                .fileType("JAVA")
                .status(MigrationStatus.SCANNED)
                .build();

        when(repository.findByStatusIn(anyCollection())).thenReturn(List.of(failingStatus, healthyStatus));
        when(aiService.preAnalyzeFile(eq("LegacyAction.java"), eq("JAVA"), anyString(), isNull()))
                .thenThrow(new RuntimeException("LLM timeout"));

        preAnalysisService.analyzeAllScannedFiles(null);

        assertEquals(MigrationStatus.ANALYSIS_FAILED, failingStatus.getStatus());
        assertEquals("LLM timeout", failingStatus.getErrorMessage());
        assertEquals(MigrationStatus.ANALYZED, healthyStatus.getStatus());
        assertEquals("TRIVIAL", healthyStatus.getMigrationComplexity());
    }
}
