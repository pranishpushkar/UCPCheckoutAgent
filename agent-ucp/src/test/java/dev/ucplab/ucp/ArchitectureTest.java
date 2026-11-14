package dev.ucplab.ucp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ucplab.core.agent.ModelKey;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.ucp");

    // Covers: section 5.2 (only agent-adk may import ADK types)
    @Test
    void architecture_noAdkTypes_outsideAgentAdk() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("com.google.adk..", "com.google.genai..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: section 5.2, 8.4 (agent-ucp uses java.net.http and core's CanonicalJson, not Jackson)
    @Test
    void architecture_noJackson_inAgentUcp() {
        noClasses()
                .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: I8 (the model key is handed out only in agent-adk's gemini package; nothing here reads it)
    @Test
    void architecture_noCallToModelKeyReveal_always() {
        noClasses()
                .should().callMethod(ModelKey.class, "reveal")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: section 5.2 (only agent-service may import Spring, Jakarta Servlet or Jackson 3 types; m6-plan 2.2)
    @Test
    void architecture_noSpringTypes_outsideAgentService() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.servlet..", "tools.jackson..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: 7.8 (no synchronized block or method anywhere in agent-ucp's main source: the breaker and the rate limiter
    // use a ReentrantLock around in-memory state only, never across a call)
    @Test
    void source_hasNoSynchronized_always() throws java.io.IOException {
        java.util.regex.Pattern word = java.util.regex.Pattern.compile("\\bsynchronized\\b");
        java.util.List<java.nio.file.Path> sources;
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }

        org.assertj.core.api.Assertions.assertThat(sources).isNotEmpty();
        for (java.nio.file.Path source : sources) {
            org.assertj.core.api.Assertions.assertThat(word.matcher(java.nio.file.Files.readString(source)).find())
                    .as(source.toString()).isFalse();
        }
    }
}
