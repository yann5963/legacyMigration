package com.migration.service;

import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.parser.LegacyCodeParserService;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ParseProblemException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Service pour scanner le projet source et construire un graphe de dépendances des fichiers Java.
 * SourceScannerService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceScannerService {

    private final MigrationFileStatusRepository repository;
    private final LegacyCodeParserService parserService;

    public int scanProject(String sourceRootPath) {
        return scanProject(sourceRootPath, List.of(), true);
    }

    public int scanProject(String sourceRootPath, List<String> manualEntryPoints, boolean autoDetectEntryPoints) {
        log.info("Démarrage du scan du projet : {}", sourceRootPath);
        Path rootPath = Paths.get(sourceRootPath).toAbsolutePath().normalize();
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            log.error("Chemin invalide ou introuvable : {}", sourceRootPath);
            throw new IllegalArgumentException("Chemin du projet source invalide.");
        }

        List<Path> relevantPaths = new ArrayList<>();
        try (var paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isRelevantFile)
                 .forEach(relevantPaths::add);
        } catch (IOException e) {
            log.error("Erreur lors du scan du répertoire : {}", sourceRootPath, e);
            throw new RuntimeException("Erreur lors du scan", e);
        }

        Map<String, MigrationFileStatus> fileStatusMap = new HashMap<>();
        Map<String, List<String>> dependencyGraph = new HashMap<>();
        Map<String, String> fqcnToPath = new HashMap<>();
        Map<String, CompilationUnit> parsedFiles = new HashMap<>();
        Map<String, Set<String>> entryPointTypes = new HashMap<>();
        Set<String> manualPaths = new LinkedHashSet<>();
        if (manualEntryPoints != null) {
            manualEntryPoints.stream().filter(Objects::nonNull)
                    .flatMap(value -> Arrays.stream(value.split("[,\\r\\n]+")))
                    .map(String::trim).filter(value -> !value.isEmpty())
                    .map(value -> rootPath.resolve(value.replace('\\', java.io.File.separatorChar)
                            .replace('/', java.io.File.separatorChar)).normalize().toString())
                    .forEach(manualPaths::add);
        }

        for (Path path : relevantPaths) {
            String fileName = path.getFileName().toString();
            String fileType = getFileExtension(fileName);
            String absPath = path.toAbsolutePath().toString();

            MigrationFileStatus status = MigrationFileStatus.builder()
                    .sourceFile(fileName)
                    .absolutePath(absPath)
                    .fileType(fileType)
                    .status(MigrationStatus.SCANNED)
                    .isConverted(false)
                    .isValidated(false)
                    .layer(1) // Default layer
                    .build();
            fileStatusMap.put(absPath, status);
            dependencyGraph.put(absPath, new ArrayList<>());

            if (fileType.equals("JAVA")) {
                try {
                    CompilationUnit cu = parserService.parseJavaFile(absPath);
                    parsedFiles.put(absPath, cu);
                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cid -> {
                        cid.getFullyQualifiedName().ifPresent(fqcn -> fqcnToPath.put(fqcn, absPath));
                    });
                } catch (IOException | ParseProblemException e) {
                    log.warn("Impossible de parser le fichier : {}", absPath, e);
                }
            }
            CompilationUnit cu = parsedFiles.get(absPath);
            Set<String> types = detectEntryPointTypes(fileName, cu);
            entryPointTypes.put(absPath, types);
            status.setEntryPoint(manualPaths.contains(absPath) || (autoDetectEntryPoints && !types.isEmpty()));
        }

        for (String manualPath : manualPaths) {
            if (!fileStatusMap.containsKey(manualPath)) {
                log.warn("Point d'entrée manuel introuvable parmi les fichiers scannés : {}", manualPath);
            }
        }

        // Build dependency graph based on imports and intra-package usages
        for (Path path : relevantPaths) {
            String absPath = path.toAbsolutePath().toString();
            CompilationUnit cu = parsedFiles.get(absPath);
            if (cu != null) {
                MigrationFileStatus sourceStatus = fileStatusMap.get(absPath);
                String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                cu.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).forEach(t -> {
                    String fqcn = packageName.isEmpty() ? t.getNameAsString() : packageName + "." + t.getNameAsString();
                    if (fqcnToPath.containsKey(fqcn)) {
                        addDependency(absPath, fqcnToPath.get(fqcn), sourceStatus, dependencyGraph);
                    }
                    if (fqcnToPath.containsKey(t.getNameWithScope())) {
                        addDependency(absPath, fqcnToPath.get(t.getNameWithScope()), sourceStatus, dependencyGraph);
                    }
                });

                cu.getImports().forEach(imp -> {
                    String importedName = imp.getNameAsString();
                    if (imp.isAsterisk() && !imp.isStatic()) {
                        fqcnToPath.forEach((fqcn, targetPath) -> {
                            int separator = fqcn.lastIndexOf('.');
                            if (separator > 0 && fqcn.substring(0, separator).equals(importedName)) {
                                addDependency(absPath, targetPath, sourceStatus, dependencyGraph);
                            }
                        });
                    }
                    String importedType = imp.isStatic() && !imp.isAsterisk() && importedName.contains(".")
                            ? importedName.substring(0, importedName.lastIndexOf('.')) : importedName;
                    if (fqcnToPath.containsKey(importedType)) {
                        addDependency(absPath, fqcnToPath.get(importedType), sourceStatus, dependencyGraph);
                    }
                });
            }
            MigrationFileStatus status = fileStatusMap.get(absPath);
            if (status.isEntryPoint() && "XML".equals(status.getFileType())) {
                addXmlDependencies(path, status, fqcnToPath, dependencyGraph);
            }
        }

        calculateReachability(rootPath, fileStatusMap, dependencyGraph, entryPointTypes);

        // Calculate layers (Topological sort approximation)
        boolean changed;
        int maxIterations = 100;
        do {
            changed = false;
            for (Map.Entry<String, List<String>> entry : dependencyGraph.entrySet()) {
                String sourcePath = entry.getKey();
                MigrationFileStatus sourceStatus = fileStatusMap.get(sourcePath);
                int maxDepLayer = 0;
                for (String depPath : entry.getValue()) {
                    MigrationFileStatus depStatus = fileStatusMap.get(depPath);
                    if (depStatus != null && depStatus.getLayer() > maxDepLayer) {
                        maxDepLayer = depStatus.getLayer();
                    }
                }
                if (sourceStatus.getLayer() <= maxDepLayer) {
                    sourceStatus.setLayer(maxDepLayer + 1);
                    changed = true;
                }
            }
            maxIterations--;
        } while (changed && maxIterations > 0);

        repository.saveAll(fileStatusMap.values());

        log.info("Scan terminé. {} fichiers trouvés.", fileStatusMap.size());
        return fileStatusMap.size();
    }

    private Set<String> detectEntryPointTypes(String fileName, CompilationUnit cu) {
        Set<String> types = new LinkedHashSet<>();
        if (Set.of("web.xml", "struts-config.xml", "faces-config.xml", "applicationcontext.xml")
                .contains(fileName.toLowerCase(Locale.ROOT))) {
            types.add("WEB");
        }
        if (cu != null) {
            if (cu.findAll(MethodDeclaration.class).stream().anyMatch(method ->
                    method.getNameAsString().equals("main") && method.isPublic() && method.isStatic()
                            && method.getType().isVoidType() && method.getParameters().size() == 1
                            && isMainParameter(method))) {
                types.add("BATCH");
            }
            if (cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .flatMap(declaration -> declaration.getExtendedTypes().stream())
                    .anyMatch(type -> Set.of("HttpServlet", "Action", "DispatchAction").contains(type.getNameAsString()))) {
                types.add("WEB");
            }
        }
        return types;
    }

    private boolean isMainParameter(MethodDeclaration method) {
        var parameter = method.getParameter(0);
        var type = parameter.getType();
        if (!parameter.isVarArgs()) {
            if (!type.isArrayType()) {
                return false;
            }
            type = type.asArrayType().getComponentType();
        }
        return type.isClassOrInterfaceType()
                && Set.of("String", "java.lang.String").contains(type.asClassOrInterfaceType().getNameWithScope());
    }

    private void addDependency(String source, String target, MigrationFileStatus status,
                               Map<String, List<String>> graph) {
        if (!source.equals(target) && !graph.get(source).contains(target)) {
            graph.get(source).add(target);
            com.migration.model.ContextReference ref = new com.migration.model.ContextReference();
            ref.setFilePath(target);
            ref.setMode(com.migration.model.ContextMode.SIGNATURE_ONLY);
            status.getContextFiles().add(ref);
        }
    }

    private void addXmlDependencies(Path path, MigrationFileStatus status, Map<String, String> fqcnToPath,
                                    Map<String, List<String>> graph) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            var document = builder.parse(path.toFile());
            var elements = document.getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                Set<String> classNames = new LinkedHashSet<>();
                for (String attribute : List.of("type", "class")) {
                    if (element.hasAttribute(attribute)) {
                        classNames.add(element.getAttribute(attribute).trim());
                    }
                }
                if (Set.of("servlet-class", "filter-class", "listener-class").contains(element.getLocalName())) {
                    classNames.add(element.getTextContent().trim());
                }
                for (String className : classNames) {
                    String target = fqcnToPath.get(className);
                    if (target != null) {
                        addDependency(status.getAbsolutePath(), target, status, graph);
                    }
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.warn("Impossible de résoudre les dépendances XML de {}", path, e);
        }
    }

    private void calculateReachability(Path root, Map<String, MigrationFileStatus> files,
                                       Map<String, List<String>> graph, Map<String, Set<String>> types) {
        List<String> entries = files.entrySet().stream().filter(entry -> entry.getValue().isEntryPoint())
                .map(Map.Entry::getKey).sorted().toList();
        if (entries.isEmpty()) {
            return;
        }
        files.values().forEach(file -> file.setReachable(false));
        Map<String, Set<String>> rootsByFile = new HashMap<>();
        Map<String, Set<String>> typesByFile = new HashMap<>();
        for (String entry : entries) {
            String relativePath = root.relativize(Path.of(entry)).toString().replace('\\', '/');
            Set<String> visited = new HashSet<>();
            Deque<String> pending = new ArrayDeque<>();
            pending.add(entry);
            while (!pending.isEmpty()) {
                String current = pending.removeFirst();
                if (!visited.add(current)) {
                    continue;
                }
                files.get(current).setReachable(true);
                rootsByFile.computeIfAbsent(current, ignored -> new LinkedHashSet<>()).add(relativePath);
                typesByFile.computeIfAbsent(current, ignored -> new TreeSet<>()).addAll(types.get(entry));
                pending.addAll(graph.get(current));
            }
        }
        rootsByFile.forEach((path, roots) -> {
            files.get(path).setRootEntryPoints(String.join(", ", roots));
            files.get(path).setRootEntryPointTypes(String.join(", ", typesByFile.get(path)));
        });
    }

    private boolean isRelevantFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".java") ||
               fileName.endsWith(".xml") ||
               fileName.endsWith(".jsp") ||
               fileName.endsWith(".properties");
    }

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1).toUpperCase();
        }
        return "UNKNOWN";
    }
}
