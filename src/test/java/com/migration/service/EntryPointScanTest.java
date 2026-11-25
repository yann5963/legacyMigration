package com.migration.service;

import com.migration.model.MigrationFileStatus;
import com.migration.parser.LegacyCodeParserService;
import com.migration.repository.MigrationFileStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntryPointScanTest {
    @TempDir
    Path root;

    private MigrationFileStatusRepository repository;
    private SourceScannerService scanner;

    @BeforeEach
    void setUp() {
        repository = mock(MigrationFileStatusRepository.class);
        scanner = new SourceScannerService(repository, new LegacyCodeParserService());
    }

    @Test
    void descriptorsAndMainPropagateMultipleRootsThroughCycles() throws Exception {
        write("web.xml", """
                <!DOCTYPE web-app SYSTEM "http://invalid.example/web-app.dtd">
                <web-app><servlet><servlet-class>app.Front</servlet-class></servlet></web-app>
                """);
        write("struts-config.xml", "<struts-config><action-mappings><action type=\"app.Front\"/></action-mappings></struts-config>");
        write("Front.java", "package app; class Front { Shared shared; }");
        write("Bootstrap.java", "package app; class Bootstrap { public static void main(String[] args) {} Shared shared; }");
        write("Shared.java", "package app; class Shared { Helper helper; }");
        write("Helper.java", "package app; class Helper { Shared shared; }");
        write("Unused.java", "package app; class Unused {}");

        assertEquals(7, scanner.scanProject(root.toString()));
        Map<String, MigrationFileStatus> files = savedFiles();
        assertTrue(files.get("web.xml").isEntryPoint());
        assertTrue(files.get("Bootstrap.java").isEntryPoint());
        assertFalse(files.get("Front.java").isEntryPoint());
        assertTrue(files.get("Helper.java").isReachable());
        String roots = files.get("Helper.java").getRootEntryPoints();
        assertTrue(roots.contains("web.xml"));
        assertTrue(roots.contains("struts-config.xml"));
        assertTrue(roots.contains("Bootstrap.java"));
        assertEquals(3, roots.split(",").length);
        assertTrue(files.get("Shared.java").getRootEntryPointTypes().contains("BATCH"));
        assertTrue(files.get("Shared.java").getRootEntryPointTypes().contains("WEB"));
        assertFalse(files.get("Unused.java").isReachable());
        assertNull(files.get("Unused.java").getRootEntryPoints());
        assertEquals(1, files.get("web.xml").getContextFiles().size());
    }

    @Test
    void manualPathsSupportCommasNewlinesAndWindowsSeparatorsWithDetectionDisabled() throws Exception {
        write("src/Start.java", "class Start { Helper helper; }");
        write("src/Helper.java", "class Helper {}");
        write("other/Start.java", "class Other {}");
        write("web.xml", "<web-app/>");

        scanner.scanProject(root.toString(), List.of(" src\\Start.java,\nweb.xml "), false);

        Map<String, MigrationFileStatus> files = savedFiles();
        assertTrue(files.get("src/Start.java").isEntryPoint());
        assertFalse(files.get("other/Start.java").isEntryPoint());
        assertTrue(files.get("src/Helper.java").isReachable());
        assertEquals("src/Start.java", files.get("src/Helper.java").getRootEntryPoints());
        assertFalse(files.get("other/Start.java").isReachable());
    }

    @Test
    void noEntryPointsKeepsAllFilesReachableAndAutoDetectionCanBeDisabled() throws Exception {
        write("Bootstrap.java", "class Bootstrap { public static void main(String... args) {} }");
        write("Unused.java", "class Unused {}");
        scanner.scanProject(root.toString(), List.of(), false);
        assertTrue(savedFiles().values().stream().allMatch(f -> f.isReachable() && !f.isEntryPoint()));
    }

    @Test
    void noDetectedOrMatchedManualEntryPointKeepsAllFilesReachable() throws Exception {
        write("Helper.java", "class Helper {}");
        write("settings.properties", "name=legacy");

        scanner.scanProject(root.toString(), List.of("missing.java"), true);

        assertTrue(savedFiles().values().stream()
                .allMatch(file -> file.isReachable() && !file.isEntryPoint() && file.getRootEntryPoints() == null));
    }

    @Test
    void detectsServletActionsAndOnlyValidMainSignatures() throws Exception {
        write("Front.java", "class Front extends jakarta.servlet.http.HttpServlet {}");
        write("Login.java", "class Login extends org.apache.struts.action.Action {}");
        write("Dispatch.java", "class Dispatch extends org.apache.struts.actions.DispatchAction {}");
        write("Bootstrap.java", "class Bootstrap { public static void main(java.lang.String... args) {} }");
        write("NotMain.java", "class NotMain { public void main(String[] args) {} }");
        write("faces-config.xml", "<faces-config/>");
        write("applicationContext.xml", "<beans/>");

        scanner.scanProject(root.toString());

        Map<String, MigrationFileStatus> files = savedFiles();
        assertTrue(files.values().stream().filter(f -> !f.getSourceFile().equals("NotMain.java"))
                .allMatch(MigrationFileStatus::isEntryPoint));
        assertFalse(files.get("NotMain.java").isReachable());
    }

    private void write(String relative, String content) throws Exception {
        Path path = root.resolve(relative.replace('/', java.io.File.separatorChar));
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private Map<String, MigrationFileStatus> savedFiles() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<MigrationFileStatus>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        return StreamSupport.stream(captor.getValue().spliterator(), false).collect(Collectors.toMap(
                f -> root.relativize(Path.of(f.getAbsolutePath())).toString().replace('\\', '/'),
                Function.identity()));
    }
}
