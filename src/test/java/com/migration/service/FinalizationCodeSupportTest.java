package com.migration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class FinalizationCodeSupportTest {
    private final FinalizationCodeSupport support = new FinalizationCodeSupport();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesDeduplicatedWindowsDiagnosticsWithSymbolContinuations() {
        String block = """
                [ERROR] /E:/target/src/main/java/a/Order.java:[27,5] cannot find symbol
                  symbol:   class CustomerDto
                  location: class a.Order
                """;
        var errors = support.parseErrors("[INFO] private full build log\n" + block
                + "[INFO] end of compiler output\n" + block);
        assertEquals(1, errors.size());
        assertEquals("E:/target/src/main/java/a/Order.java", errors.get(0).path());
        assertEquals(27, errors.get(0).line());
        assertTrue(errors.get(0).message().contains("class CustomerDto"));
        assertFalse(errors.get(0).render().contains("private full build log"));
    }

    @Test
    void slicesIncludeExactlyFifteenSurroundingLinesAndHeadersNotDistantLogic() {
        String code = "package a;\nimport java.util.List;\npublic class Order {\n"
                + IntStream.rangeClosed(4, 100).mapToObj(i -> "    int field" + i + ";")
                .collect(Collectors.joining("\n")) + "\n}";
        String slices = support.codeSlices(code, List.of(
                new FinalizationCodeSupport.CompilerError("Order.java", 50, 1, "bad field")));
        assertTrue(slices.contains("1: package a;"));
        assertTrue(slices.contains("2: import java.util.List;"));
        assertTrue(slices.contains("3: public class Order {"));
        assertTrue(slices.contains("35:     int field35;"));
        assertTrue(slices.contains("65:     int field65;"));
        assertFalse(slices.contains("int field34;"));
        assertFalse(slices.contains("int field66;"));
        assertFalse(slices.contains("int field95;"));
    }

    @Test
    void cleansLegacyImportsAndAlignsPackageIncludingJava21Syntax() {
        String cleaned = support.clean("""
                package old;
                import javax.servlet.http.HttpServletRequest;
                import org.apache.struts.action.Action;
                import java.util.List;
                public class Example {
                    public String value(Object object) {
                        return switch (object) { case String text -> text; default -> ""; };
                    }
                }
                """, "src\\main\\java\\com\\acme\\Example.java");
        assertTrue(cleaned.contains("package com.acme;"));
        assertTrue(cleaned.contains("import java.util.List;"));
        assertFalse(cleaned.contains("javax.servlet"));
        assertFalse(cleaned.contains("org.apache.struts"));
        assertTrue(cleaned.contains("public String value"));
    }

    @Test
    void acceptsSurgicalPatchButRejectsMethodDeletionOverloadLossAndInvalidJava() throws Exception {
        String original = "public class A { public int value() { return missing; }"
                + " public int value(int n) { return n; } private void work() { System.out.println(1); } }";
        String patch = patch("return missing;", "return 42;");
        String updated = support.applyPatch(original, patch, mapper, original);
        assertTrue(updated.contains("return 42;"));
        assertTrue(updated.contains("public int value(int n)"));
        assertThrows(IllegalArgumentException.class, () -> support.applyPatch(original,
                patch("public int value(int n) { return n; }", ""), mapper, original));
        assertThrows(IllegalArgumentException.class, () -> support.applyPatch(original,
                patch("private void work() { System.out.println(1); }", ""), mapper, original));
        assertThrows(IllegalArgumentException.class, () -> support.applyPatch(original,
                patch("return missing;", "if ("), mapper, original));
        assertThrows(IllegalArgumentException.class, () -> support.applyPatch(original,
                patch("return missing;", ""), mapper, original));
    }

    @Test
    void rejectsAmbiguousOverlappingAndOutOfSlicePatches() throws Exception {
        String code = "class A { int a = 1; int b = 1; int c = missing; }";
        assertThrows(IllegalArgumentException.class, () ->
                support.applyPatch(code, patch("1", "2"), mapper, code));
        assertThrows(IllegalArgumentException.class, () ->
                support.applyPatch(code, patch("int c = missing;", "int c = 2;"), mapper, "int a = 1;"));
        String overlapping = mapper.writeValueAsString(new FinalizationCodeSupport.FixPatch(List.of(
                new FinalizationCodeSupport.Replacement("int c = missing;", "int c = 2;"),
                new FinalizationCodeSupport.Replacement("missing", "3"))));
        assertThrows(IllegalArgumentException.class, () -> support.applyPatch(code, overlapping, mapper, code));
    }

    private String patch(String original, String replacement) throws Exception {
        return mapper.writeValueAsString(new FinalizationCodeSupport.FixPatch(
                List.of(new FinalizationCodeSupport.Replacement(original, replacement))));
    }
}
