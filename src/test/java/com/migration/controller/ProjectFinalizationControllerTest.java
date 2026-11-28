package com.migration.controller;

import com.migration.service.ProjectFinalizationJobService;
import com.migration.service.ProjectFinalizationService;
import com.migration.core.model.TargetProjectConfig;
import com.migration.core.model.AiConfigProperties;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.ProjectConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectFinalizationControllerTest {

    @Test
    void configurationSavePreservesServerBuildTraceOnlyForTheSameTargetProject() {
        var repository = mock(ProjectConfigurationRepository.class);
        var previous = ProjectConfiguration.builder().id(1L).targetProjectPath("target-project")
                .lastBuildSuccess(false).lastBuildErrors(3).lastBuildDiagnostic("Exact diagnostic").build();
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(previous));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var controller = new ConfigurationController(repository, mock(TargetProjectConfig.class),
                mock(AiConfigProperties.class));
        var sameTarget = ProjectConfiguration.builder().targetProjectPath("target-project").build();
        controller.updateConfiguration(sameTarget);
        assertEquals(3, sameTarget.getLastBuildErrors());
        assertEquals("Exact diagnostic", sameTarget.getLastBuildDiagnostic());
        var otherTarget = ProjectConfiguration.builder().targetProjectPath("another-project")
                .lastBuildSuccess(true).lastBuildErrors(99).build();
        controller.updateConfiguration(otherTarget);
        assertNull(otherTarget.getLastBuildSuccess());
        assertEquals(0, otherTarget.getLastBuildErrors());
    }

    @Test
    void actionsAreAcceptedTrackedAndConcurrentActionsAreRejected() throws Exception {
        var finalization = mock(ProjectFinalizationService.class);
        AtomicReference<Runnable> pending = new AtomicReference<>();
        TaskExecutor executor = pending::set;
        var jobs = new ProjectFinalizationJobService(finalization, executor);
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectFinalizationController(finalization, jobs)).build();
        mvc.perform(post("/api/migration/finalization/prepare"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.running").value(true));
        mvc.perform(post("/api/migration/finalization/build"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").isNotEmpty());
        mvc.perform(get("/api/migration/finalization/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation").value("PREPARE"));
        pending.get().run();
        verify(finalization).prepareTargetProject();
        assertFalse(jobs.getStatus().running());
        mvc.perform(get("/api/migration/finalization/status"))
                .andExpect(jsonPath("$.running").value(false));
    }

    @Test
    void editedPromptAndModelReachOnlyTheRequestedArtifactAndErrorsRemainVisible() throws Exception {
        var finalization = mock(ProjectFinalizationService.class);
        var jobs = new ProjectFinalizationJobService(finalization, Runnable::run);
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectFinalizationController(finalization, jobs)).build();
        when(finalization.getFixPrompt(9L)).thenReturn(new ProjectFinalizationService.PromptPreview(
                9L, "[ERROR] exact compiler error", "last saved prompt"));
        when(finalization.fixSingleArtifact(9L, "edited", "coder"))
                .thenThrow(new IllegalStateException("Maven unavailable"));
        mvc.perform(get("/api/migration/finalization/artifacts/9/fix-prompt"))
                .andExpect(jsonPath("$.prompt").value("last saved prompt"))
                .andExpect(jsonPath("$.compilerErrors").value("[ERROR] exact compiler error"));
        mvc.perform(post("/api/migration/finalization/artifacts/9/fix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customPrompt\":\"edited\",\"modelOverride\":\"coder\"}"))
                .andExpect(status().isAccepted());
        verify(finalization).fixSingleArtifact(9L, "edited", "coder");
        mvc.perform(get("/api/migration/finalization/status"))
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.error").value("Maven unavailable"));
        mvc.perform(post("/api/migration/finalization/auto-fix?maxIterations=51"))
                .andExpect(status().isBadRequest());
    }
}
