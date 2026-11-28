package com.migration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class FinalizationCodeSupport {

    private static final Pattern ERROR = Pattern.compile(
            "^\\[ERROR]\\s+(.+?\\.java):\\[(\\d+),(\\d+)]\\s*(.*)$");
    private static final Pattern CONTINUATION = Pattern.compile(
            "^(?:\\[ERROR]\\s*)?\\s*(?:symbol\\s*:|location\\s*:|cannot find symbol|"
                    + "symbole\\s*:|emplacement\\s*:|required\\s*:|found\\s*:|reason\\s*:).*$");
    static final int MAX_CONTEXT_CHARS = 24000;

    public record CompilerError(String path, int line, int column, String message) {
        public String render() {
            return "[ERROR] " + path + ":[" + line + "," + column + "] " + message;
        }
    }

    public List<CompilerError> parseErrors(String output) {
        Map<String, CompilerError> errors = new LinkedHashMap<>();
        CompilerError current = null;
        for (String line : output.replaceAll("\\x1B\\[[;\\d]*m", "").split("\\R")) {
            Matcher matcher = ERROR.matcher(line);
            if (matcher.matches()) {
                if (current != null) {
                    errors.putIfAbsent(current.render(), current);
                }
                current = new CompilerError(matcher.group(1).trim().replace('\\', '/')
                        .replaceFirst("^/([A-Za-z]:/)", "$1"),
                        Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), matcher.group(4));
            } else if (current != null && CONTINUATION.matcher(line).matches()) {
                current = new CompilerError(current.path(), current.line(), current.column(),
                        current.message() + "\n" + line.replaceFirst("^\\[ERROR]\\s*", "").strip());
            } else if (current != null) {
                errors.putIfAbsent(current.render(), current);
                current = null;
            }
        }
        if (current != null) {
            errors.putIfAbsent(current.render(), current);
        }
        return List.copyOf(errors.values());
    }

    public CompilationUnit parse(String code) {
        var result = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(code);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new IllegalArgumentException("Java invalide : " + result.getProblems());
        }
        return result.getResult().orElseThrow();
    }

    public String clean(String code, String relativePath) {
        CompilationUnit unit = parse(code);
        unit.getImports().removeIf(i -> i.getNameAsString().equals("javax.servlet")
                || i.getNameAsString().startsWith("javax.servlet.")
                || i.getNameAsString().equals("org.apache.struts")
                || i.getNameAsString().startsWith("org.apache.struts."));
        String path = relativePath.replace('\\', '/');
        String root = path.startsWith("src/test/java/") ? "src/test/java/" : "src/main/java/";
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Source Java hors de src/main/java ou src/test/java : " + path);
        }
        String packageName = path.substring(root.length(), path.lastIndexOf('/')).replace('/', '.');
        if (packageName.isEmpty()) {
            unit.removePackageDeclaration();
        } else {
            unit.setPackageDeclaration(packageName);
        }
        return unit.toString();
    }

    public String codeSlices(String code, List<CompilerError> errors) {
        CompilationUnit unit = parse(code);
        String[] lines = code.split("\\R", -1);
        Set<Integer> selected = new TreeSet<>();
        unit.getPackageDeclaration().ifPresent(n -> addRange(selected, n));
        unit.getImports().forEach(n -> addRange(selected, n));
        unit.findAll(TypeDeclaration.class).forEach(rawType -> {
            TypeDeclaration<?> type = rawType;
            int begin = type.getBegin().orElseThrow().line;
            int end = type.getMembers().isEmpty() ? type.getEnd().orElseThrow().line - 1
                    : type.getMembers().get(0).getBegin().orElseThrow().line - 1;
            // Only the type header, never all fields/methods of a dependency or enclosing class.
            for (int i = begin; i <= Math.max(begin, end); i++) {
                selected.add(i);
            }
        });
        for (CompilerError error : errors) {
            for (int i = Math.max(1, error.line() - 15); i <= Math.min(lines.length, error.line() + 15); i++) {
                selected.add(i);
            }
        }
        StringBuilder slices = new StringBuilder();
        int previous = 0;
        for (int number : selected) {
            if (number > lines.length) {
                continue;
            }
            if (previous != 0 && number != previous + 1) {
                slices.append("...\n");
            }
            slices.append(number).append(": ").append(lines[number - 1]).append('\n');
            previous = number;
        }
        if (slices.length() > MAX_CONTEXT_CHARS) {
            throw new IllegalArgumentException("Extraits trop volumineux : correction manuelle requise.");
        }
        return slices.toString();
    }

    private void addRange(Set<Integer> selected, Node node) {
        node.getRange().ifPresent(range -> {
            for (int i = range.begin.line; i <= range.end.line; i++) {
                selected.add(i);
            }
        });
    }

    public record Replacement(String original, String replacement) { }
    public record FixPatch(List<Replacement> replacements) { }

    public String applyPatch(String originalCode, String response, ObjectMapper mapper, String permittedSlices) {
        if (response == null || response.length() > 128000) {
            throw new IllegalArgumentException("Reponse IA absente ou trop volumineuse.");
        }
        FixPatch patch;
        try {
            patch = mapper.readValue(response, FixPatch.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("La correction IA n'est pas un patch JSON valide.", e);
        }
        if (patch == null || patch.replacements() == null || patch.replacements().isEmpty()
                || patch.replacements().size() > 30) {
            throw new IllegalArgumentException("Patch IA vide ou trop volumineux.");
        }
        record Edit(int start, int end, String replacement) { }
        List<Edit> edits = new ArrayList<>();
        for (Replacement replacement : patch.replacements()) {
            if (replacement.original() == null || replacement.original().isBlank()
                    || replacement.replacement() == null) {
                throw new IllegalArgumentException("Remplacement IA invalide.");
            }
            int start = originalCode.indexOf(replacement.original());
            if (start < 0 || originalCode.indexOf(replacement.original(), start + 1) >= 0) {
                throw new IllegalArgumentException("Le texte a remplacer est absent ou ambigu.");
            }
            String visibleCode = permittedSlices.replaceAll("(?m)^\\d+: ", "").replace("\r\n", "\n");
            if (!visibleCode.contains(replacement.original().replace("\r\n", "\n"))) {
                throw new IllegalArgumentException("Le patch sort des extraits de code autorises.");
            }
            edits.add(new Edit(start, start + replacement.original().length(), replacement.replacement()));
        }
        edits.sort(java.util.Comparator.comparingInt(Edit::start));
        for (int i = 1; i < edits.size(); i++) {
            if (edits.get(i).start() < edits.get(i - 1).end()) {
                throw new IllegalArgumentException("Remplacements IA qui se chevauchent.");
            }
        }
        StringBuilder updated = new StringBuilder(originalCode);
        for (int i = edits.size() - 1; i >= 0; i--) {
            Edit edit = edits.get(i);
            updated.replace(edit.start(), edit.end(), edit.replacement());
        }
        assertPreservesBusinessMethods(originalCode, updated.toString());
        if (originalCode.equals(updated.toString())) {
            throw new IllegalArgumentException("Le patch IA ne modifie pas le fichier.");
        }
        return updated.toString();
    }

    public void assertPreservesBusinessMethods(String original, String updated) {
        CompilationUnit before = parse(original);
        CompilationUnit after = parse(updated);
        if (!before.getPackageDeclaration().map(Object::toString)
                .equals(after.getPackageDeclaration().map(Object::toString))
                || !typeNames(before).equals(typeNames(after))) {
            throw new IllegalArgumentException("Le patch modifie le package ou les types declares.");
        }
        Map<String, CallableDeclaration<?>> remaining = callables(after);
        for (var entry : callables(before).entrySet()) {
            CallableDeclaration<?> replacement = remaining.get(entry.getKey());
            if (replacement == null) {
                throw new IllegalArgumentException("Suppression ou changement de signature interdit : " + entry.getKey());
            }
            if (entry.getValue().isPublic() && !replacement.isPublic()) {
                throw new IllegalArgumentException("Reduction de visibilite interdite : " + entry.getKey());
            }
            if (entry.getValue() instanceof MethodDeclaration oldMethod
                    && oldMethod.getBody().filter(b -> !b.isEmpty()).isPresent()
                    && replacement instanceof MethodDeclaration newMethod
                    && newMethod.getBody().filter(b -> !b.isEmpty()).isEmpty()) {
                throw new IllegalArgumentException("Suppression du corps metier interdite : " + entry.getKey());
            }
        }
        Set<String> fieldsAfter = fields(after);
        if (!fieldsAfter.containsAll(fields(before))) {
            throw new IllegalArgumentException("Suppression de champs interdite.");
        }
    }

    private Set<String> typeNames(CompilationUnit unit) {
        return unit.findAll(TypeDeclaration.class).stream().map(this::owner).collect(Collectors.toSet());
    }

    private Set<String> fields(CompilationUnit unit) {
        Set<String> names = new LinkedHashSet<>();
        unit.findAll(FieldDeclaration.class).forEach(field -> field.getVariables()
                .forEach(v -> names.add(owner(field) + "#" + v.getNameAsString())));
        return names;
    }

    private Map<String, CallableDeclaration<?>> callables(CompilationUnit unit) {
        Map<String, CallableDeclaration<?>> methods = new LinkedHashMap<>();
        unit.findAll(CallableDeclaration.class).forEach(method -> {
            String key = owner(method) + "#" + method.getSignature();
            if (methods.putIfAbsent(key, method) != null) {
                throw new IllegalArgumentException("Signatures AST ambigues : revue manuelle requise pour " + key);
            }
        });
        return methods;
    }

    private String owner(Node node) {
        List<String> names = new ArrayList<>();
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof TypeDeclaration<?> type) {
                names.add(0, type.getNameAsString());
            }
        }
        return String.join(".", names);
    }
}
