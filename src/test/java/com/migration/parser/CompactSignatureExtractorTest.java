package com.migration.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactSignatureExtractorTest {

    private final CompactSignatureExtractor extractor = new CompactSignatureExtractor();

    @Test
    void extractsFqcnTableAndCompactSignatureWithoutMethodBodies() {
        String code = """
                package com.migration.target.entity;

                @Entity
                @Table(name = "ENSEIGNANT")
                public class EnseignantEntity {
                    @Id private Long id;
                    private String nom;
                    public String getNom() { return secretBody(); }
                    private String secretBody() { return nom; }
                }
                """;

        CompactSignatureExtractor.Extraction result = extractor.extract(code, "ENTITY", "x/EnseignantEntity.java");

        assertEquals("com.migration.target.entity.EnseignantEntity", result.fqcn());
        assertEquals("ENSEIGNANT", result.sqlTableName());
        assertTrue(result.compactSignature().contains("@Table(name = \"ENSEIGNANT\")"));
        assertTrue(result.compactSignature().contains("public String getNom();"));
        assertFalse(result.compactSignature().contains("secretBody"));
        assertEquals(java.util.List.of("id", "nom"), result.fieldNames());
    }

    @Test
    void fallsBackOnSyntaxError() {
        String code = "package a.b;\n@Table(name = \"T_X\")\npublic class Broken {\n  private int x;\n  public void f( {\n";

        CompactSignatureExtractor.Extraction result = extractor.extract(code, "ENTITY", "Broken.java");

        assertEquals("a.b.Broken", result.fqcn());
        assertEquals("T_X", result.sqlTableName());
        assertTrue(result.compactSignature().contains("private int x;"));
    }

    @Test
    void extractsRecordSignature() {
        CompactSignatureExtractor.Extraction result = extractor.extract(
                "package a;\npublic record AnneeScolaireDto(Long id, String label) {}\n", "DTO", "AnneeScolaireDto.java");

        assertEquals("a.AnneeScolaireDto", result.fqcn());
        assertTrue(result.compactSignature().contains("record AnneeScolaireDto(Long id, String label)"));
    }
}
