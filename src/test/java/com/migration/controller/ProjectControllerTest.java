package com.migration.controller;

import com.migration.pipeline.ProjectInitializerStep;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.repository.ProjectConfigurationRepository;
import com.migration.service.SemanticPreAnalysisService;
import com.migration.service.SourceScannerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectControllerTest {
    @Test
    void scanAcceptsEntryPointOptionsAndKeepsDefaultsForExistingClients() throws Exception {
        SourceScannerService scanner = mock(SourceScannerService.class);
        ProjectInitializerStep initializer = mock(ProjectInitializerStep.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProjectController(
                scanner, mock(SemanticPreAnalysisService.class), initializer,
                mock(MigrationFileStatusRepository.class), mock(ProjectConfigurationRepository.class))).build();
        when(scanner.scanProject("legacy", List.of(), true)).thenReturn(2);
        when(scanner.scanProject("legacy", List.of("src\\Bootstrap.java", "web.xml"), false)).thenReturn(4);

        mvc.perform(post("/api/project/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceRootPath\":\"legacy\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.filesFound").value(2));
        mvc.perform(post("/api/project/scan").contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceRootPath":"legacy","autoDetectEntryPoints":false,
                         "manualEntryPoints":["src\\\\Bootstrap.java","web.xml"]}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.filesFound").value(4));

        verify(scanner).scanProject("legacy", List.of(), true);
        verify(scanner).scanProject("legacy", List.of("src\\Bootstrap.java", "web.xml"), false);
        verify(initializer, times(2)).execute();
    }
}
