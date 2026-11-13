package dev.ucplab.faultproxy;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.faultproxy");

    // Covers: section 5.2 (only agent-adk may import ADK types)
    @Test
    void architecture_noAdkTypes_outsideAgentAdk() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("com.google.adk..", "com.google.genai..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: section 5.2, §12 (fault-proxy is JDK only: java, javax.crypto for bodyHmac, the JDK HttpServer)
    @Test
    void architecture_proxyImportsOnlyTheJdk_always() {
        classes()
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax.crypto..", "com.sun.net.httpserver..", "dev.ucplab.faultproxy..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: §12 Safety, 7.8 (no lock of any kind, so none can be held across network I/O)
    @Test
    void architecture_proxyUsesNoLocks_always() {
        noClasses()
                .should().dependOnClassesThat().resideInAPackage("java.util.concurrent.locks..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: §12 Safety, 7.8 (no synchronized block or method anywhere in the proxy's source)
    @Test
    void source_hasNoSynchronized_always() throws IOException {
        Pattern word = Pattern.compile("\\bsynchronized\\b");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }

        assertThat(sources).isNotEmpty();
        for (Path source : sources) {
            assertThat(word.matcher(Files.readString(source)).find()).as(source.toString()).isFalse();
        }
    }
}
