package dev.ucplab.ucp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.ucp");

    // Covers: section 5.2 (only agent-adk may import ADK types)
    @Test
    void architecture_noAdkTypes_outsideAgentAdk() {
        noClasses()
                .should().dependOnClassesThat().resideInAPackage("com.google.adk..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: section 5.2, 8.4 (agent-ucp uses java.net.http and core's CanonicalJson, not Jackson)
    @Test
    void architecture_noJackson_inAgentUcp() {
        noClasses()
                .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                .check(PRODUCTION_CLASSES);
    }
}
