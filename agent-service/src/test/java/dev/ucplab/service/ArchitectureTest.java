package dev.ucplab.service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.service");

    // Covers: section 5.2 (only agent-adk may import ADK types)
    @Test
    void architecture_noAdkTypes_outsideAgentAdk() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("com.google.adk..", "com.google.genai..")
                // The module has no classes yet.
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }
}
