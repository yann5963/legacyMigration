package com.migration.service;

import com.migration.model.GeneratedArtifact;
import com.migration.model.ProjectConfiguration;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.lang.model.SourceVersion;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TargetProjectPreparationService {

    public void prepare(Path root, ProjectConfiguration config, List<GeneratedArtifact> artifacts) throws Exception {
        String basePackage = value(config.getTargetGroupId(),
                value(config.getTargetBasePackage(), "com.migrated")).replace('-', '_');
        String artifactId = value(config.getTargetArtifactId(), "app-moderne");
        if (!SourceVersion.isName(basePackage) || !artifactId.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Group ID ou Artifact ID cible invalide.");
        }
        Files.createDirectories(root);
        preparePom(root, basePackage, artifactId, config, artifacts);
        Path javaRoot = ProjectFinalizationService.safeResolve(root, "src/main/java");
        Files.createDirectories(javaRoot);
        boolean hasApplication = artifacts.stream().anyMatch(a -> a.getGeneratedCode() != null
                && a.getGeneratedCode().contains("@SpringBootApplication"));
        try (var files = Files.walk(javaRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (!file.toRealPath().startsWith(root.toRealPath())) {
                    throw new IOException("Source cible situee hors du projet : " + file);
                }
                if (Files.readString(file).contains("@SpringBootApplication")) {
                    hasApplication = true;
                    break;
                }
            }
        }
        if (!hasApplication) {
            Path main = ProjectFinalizationService.safeResolve(root,
                    "src/main/java/" + basePackage.replace('.', '/') + "/MigrationApplication.java");
            writeMissing(main, """
                    package %s;

                    import org.springframework.boot.SpringApplication;
                    import org.springframework.boot.autoconfigure.SpringBootApplication;

                    @SpringBootApplication
                    public class MigrationApplication {
                        public static void main(String[] args) {
                            SpringApplication.run(MigrationApplication.class, args);
                        }
                    }
                    """.formatted(basePackage));
            if (!Files.readString(main).contains("@SpringBootApplication")) {
                throw new IOException("MigrationApplication.java existe sans @SpringBootApplication.");
            }
        }
        Path resources = ProjectFinalizationService.safeResolve(root, "src/main/resources");
        if (!Files.exists(resources.resolve("application.properties"))
                && !Files.exists(resources.resolve("application.yaml"))) {
            writeMissing(ProjectFinalizationService.safeResolve(root, "src/main/resources/application.yml"),
                    "spring:\n  application:\n    name: " + artifactId + "\n");
        }
    }

    private void preparePom(Path root, String group, String artifact, ProjectConfiguration config,
                            List<GeneratedArtifact> artifacts) throws Exception {
        Path pom = ProjectFinalizationService.safeResolve(root, "pom.xml");
        String bootVersion = value(config.getTargetSpringBootVersion(), "3.2.4");
        if (!bootVersion.matches("3\\.\\d+\\.\\d+(?:[-.][A-Za-z0-9]+)*")) {
            throw new IllegalArgumentException("La finalisation requiert une version Spring Boot 3 valide.");
        }
        writeMissing(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>%s</version>
                    <relativePath/>
                  </parent>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                  <properties><java.version>21</java.version></properties>
                  <dependencies/>
                  <build><plugins><plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                  </plugin></plugins></build>
                </project>
                """.formatted(bootVersion, group, artifact));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(pom.toFile());
        Element project = document.getDocumentElement();
        if (!"project".equals(project.getLocalName())) {
            throw new IOException("pom.xml cible invalide.");
        }
        Element dependencies = child(project, "dependencies");
        boolean changed = dependencies == null;
        if (dependencies == null) {
            dependencies = append(project, "dependencies", null);
        }
        String code = artifacts.stream().map(a -> value(a.getGeneratedCode(), "") + " "
                + value(a.getArtifactType(), "")).collect(java.util.stream.Collectors.joining("\n"));
        Map<String, String> required = new LinkedHashMap<>();
        required.put("spring-boot-starter", "org.springframework.boot");
        required.put("spring-boot-starter-test", "org.springframework.boot");
        if (code.contains("CONTROLLER") || code.contains("org.springframework.web") || code.contains("javax.servlet")) {
            required.put("spring-boot-starter-web", "org.springframework.boot");
        }
        if (code.contains("ENTITY") || code.contains("REPOSITORY") || code.contains("jakarta.persistence")) {
            required.put("spring-boot-starter-data-jpa", "org.springframework.boot");
        }
        if (code.contains("validation.") || code.contains("@Valid") || code.contains("@NotNull")) {
            required.put("spring-boot-starter-validation", "org.springframework.boot");
        }
        if (code.contains("lombok.")) {
            required.put("lombok", "org.projectlombok");
        }
        if (code.contains("java.sql") || code.contains("Jdbc") || required.containsKey("spring-boot-starter-data-jpa")) {
            required.put("postgresql", "org.postgresql");
        }
        if (code.contains("VIEW") || code.contains("thymeleaf")) {
            required.put("spring-boot-starter-thymeleaf", "org.springframework.boot");
        }
        for (var entry : required.entrySet()) {
            boolean present = false;
            for (var node = dependencies.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element dependency) {
                    Element id = child(dependency, "artifactId");
                    Element dependencyGroup = child(dependency, "groupId");
                    if (id != null && entry.getKey().equals(id.getTextContent().strip())
                            && dependencyGroup != null && entry.getValue().equals(dependencyGroup.getTextContent().strip())) {
                        present = true;
                        break;
                    }
                }
            }
            if (!present) {
                Element dependency = append(dependencies, "dependency", null);
                append(dependency, "groupId", entry.getValue());
                append(dependency, "artifactId", entry.getKey());
                if ("postgresql".equals(entry.getKey())) {
                    append(dependency, "scope", "runtime");
                } else if ("spring-boot-starter-test".equals(entry.getKey())) {
                    append(dependency, "scope", "test");
                } else if ("lombok".equals(entry.getKey())) {
                    append(dependency, "optional", "true");
                }
                changed = true;
            }
        }
        if (changed) {
            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(new DOMSource(document), new StreamResult(pom.toFile()));
        }
    }

    private Element child(Element parent, String name) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private Element append(Element parent, String name, String content) {
        Element element = parent.getOwnerDocument().createElementNS(parent.getNamespaceURI(), name);
        if (content != null) {
            element.setTextContent(content);
        }
        parent.appendChild(element);
        return element;
    }

    private void writeMissing(Path path, String content) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.strip();
    }
}
