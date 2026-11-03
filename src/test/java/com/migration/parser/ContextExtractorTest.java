package com.migration.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.util.ReflectionTestUtils;

class ContextExtractorTest {

    private LegacyCodeParserService parserService;

    @BeforeEach
    void setUp() {
        parserService = new LegacyCodeParserService();
        ReflectionTestUtils.setField(parserService, "legacyDirPath", "/dummy/path");
        parserService.init();
    }

    @Test
    void extractSignatures_ShouldKeepStructureAndRemoveMethodBodies() {
        String input = """
            package com.example;

            import java.util.List;

            @Entity
            public class User {
                private String name;

                @Id
                private Long id;

                public User() {
                    this.name = "default";
                }

                public String getName() {
                    return this.name;
                }

                public void setName(String name) {
                    this.name = name;
                }
            }
            """;

        String output = parserService.extractSignatures(input);

        assertThat(output)
            .contains("package com.example;")
            .contains("import java.util.List;")
            .contains("@Entity")
            .contains("public class User")
            .contains("private String name;")
            .contains("@Id")
            .contains("private Long id;")
            .contains("public User()")
            .contains("public String getName();")
            .contains("public void setName(String name);")
            .doesNotContain("this.name = \"default\";")
            .doesNotContain("return this.name;")
            .doesNotContain("this.name = name;");
    }
}
