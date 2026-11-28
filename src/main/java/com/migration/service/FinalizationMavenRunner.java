package com.migration.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class FinalizationMavenRunner {

    public record BuildOutput(boolean success, String output) { }

    public BuildOutput build(Path projectRoot) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder builder = windows
                ? new ProcessBuilder("cmd.exe", "/d", "/c", "mvn test-compile -B -Dstyle.color=never")
                : new ProcessBuilder("mvn", "test-compile", "-B", "-Dstyle.color=never");
        Path log = Files.createTempFile("finalization-maven-", ".log");
        Process process = null;
        try {
            process = builder.directory(projectRoot.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                throw new IOException("Le build Maven a depasse le delai de 10 minutes.");
            }
            return new BuildOutput(process.exitValue() == 0, Files.readString(log, StandardCharsets.UTF_8));
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(log);
        }
    }
}
