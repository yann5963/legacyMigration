package com.migration.service;

import com.migration.model.ContextMode;
import com.migration.model.ContextReference;
import com.migration.model.GeneratedArtifact;
import com.migration.model.MigrationFileStatus;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.repository.ProjectConfigurationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicPromptBuilderServiceTest {

    @Mock
    private MigrationFileStatusRepository fileStatusRepository;

    @Mock
    private ProjectConfigurationRepository projectConfigRepository;

    @InjectMocks
    private DynamicPromptBuilderService service;

    @BeforeEach
    void setUp() {
        ProjectConfiguration config = ProjectConfiguration.builder()
                .id(1L)
                .targetBasePackage("com.company.target")
                .build();
        when(projectConfigRepository.findById(1L)).thenReturn(Optional.of(config));
    }

    @Test
    void rootFlowsGuideBatchAndWebWithoutChangingSharedComponentRole() {
        MigrationFileStatus status = MigrationFileStatus.builder()
                .sourceFile("Shared.java").architecturalRole("BUSINESS_SERVICE")
                .rootEntryPoints("Bootstrap.java, web.xml")
                .rootEntryPointTypes("BATCH, WEB").build();

        String prompt = service.buildConversionPrompt(status, List.of());

        assertTrue(prompt.contains("Bootstrap.java, web.xml"));
        assertTrue(prompt.contains("Spring Batch"));
        assertTrue(prompt.contains("CommandLineRunner"));
        assertTrue(prompt.contains("Spring Web MVC / REST"));
        assertTrue(prompt.contains("@Service"));
    }

    @Test
    void entryPointIsIdentifiedAndDescriptorNamesSupportExistingMetadata() {
        MigrationFileStatus status = MigrationFileStatus.builder()
                .sourceFile("struts-config.xml").entryPoint(true)
                .rootEntryPoints("struts-config.xml").build();

        String prompt = service.buildConversionPrompt(status, List.of());

        assertTrue(prompt.contains("ENTRY POINT"));
        assertTrue(prompt.contains("Spring Web MVC / REST"));
    }

    @Test
    void buildConversionPrompt_forJdbcDao_includesSqlExtractionAndRecordDtoDirectives() {
        MigrationFileStatus status = MigrationFileStatus.builder()
                .sourceFile("AnneeScolDao.java")
                .architecturalRole("JDBC_DAO")
                .migrationComplexity("HIGH")
                .targetSpringArtifact("Repository")
                .functionalSummary("Accès aux années scolaires en base Informix.")
                .detectedRisks("[\"Concaténation SQL dynamique\", \"Fuites ResultSet\"]")
                .suggestedFunctionalLot("GestionScolaire")
                .build();

        String prompt = service.buildConversionPrompt(status, List.of());

        assertTrue(prompt.contains("JDBC_DAO"));
        assertTrue(prompt.contains("@Repository"));
        assertTrue(prompt.contains("JdbcClient"));
        assertTrue(prompt.contains("record"));
        assertTrue(prompt.contains("Concaténation SQL dynamique"));
        assertTrue(prompt.contains("Fuites ResultSet"));
        assertTrue(prompt.contains("GestionScolaire"));
        assertTrue(prompt.contains("com.company.target"));
        assertTrue(prompt.contains("1 -> N"));
    }

    @Test
    void buildConversionPrompt_forStrutsAction_includesControllerServiceSplitAndDependencyContext() {
        String depPath = "/legacy/AnneeScolDao.java";
        MigrationFileStatus depStatus = MigrationFileStatus.builder()
                .absolutePath(depPath)
                .sourceFile("AnneeScolDao.java")
                .functionalSummary("DAO des années scolaires.")
                .generatedArtifacts(List.of(
                        GeneratedArtifact.builder()
                                .relativeTargetPath("src/main/java/com/company/target/repository/AnneeScolRepository.java")
                                .artifactType("REPOSITORY")
                                .build()
                ))
                .build();
        when(fileStatusRepository.findByAbsolutePath(depPath)).thenReturn(Optional.of(depStatus));

        MigrationFileStatus status = MigrationFileStatus.builder()
                .sourceFile("AnneeScolAction.java")
                .architecturalRole("STRUTS_ACTION")
                .migrationComplexity("MEDIUM")
                .targetSpringArtifact("RestController")
                .functionalSummary("Action Struts de gestion des années scolaires.")
                .detectedRisks("Couplage HttpSession")
                .contextFiles(List.of(
                        ContextReference.builder().filePath(depPath).mode(ContextMode.SIGNATURE_ONLY).build()
                ))
                .build();

        String prompt = service.buildConversionPrompt(status, List.of("/legacy/ExtraHelper.java"));

        assertTrue(prompt.contains("@RestController"));
        assertTrue(prompt.contains("@Service"));
        assertTrue(prompt.contains("Couplage HttpSession"));
        assertTrue(prompt.contains("DAO des années scolaires."));
        assertTrue(prompt.contains("src/main/java/com/company/target/repository/AnneeScolRepository.java"));
        assertTrue(prompt.contains("/legacy/ExtraHelper.java"));
    }

    @Test
    void buildConversionPrompt_forSingletonAndDtoModel_appliesSpecificDirectives() {
        MigrationFileStatus singletonStatus = MigrationFileStatus.builder()
                .sourceFile("RequeteSingletonAnneeScol.java")
                .architecturalRole("SINGLETON")
                .build();
        String singletonPrompt = service.buildConversionPrompt(singletonStatus, null);
        assertTrue(singletonPrompt.contains("@Component"));
        assertTrue(singletonPrompt.contains("static"));

        MigrationFileStatus dtoStatus = MigrationFileStatus.builder()
                .sourceFile("AnneeScolDto.java")
                .architecturalRole("DTO_MODEL")
                .build();
        String dtoPrompt = service.buildConversionPrompt(dtoStatus, null);
        assertTrue(dtoPrompt.contains("record"));
        assertTrue(dtoPrompt.contains("@Entity"));
    }
}
