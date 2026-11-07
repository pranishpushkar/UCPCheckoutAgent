package dev.ucplab.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.core");

    // Covers: section 5.2 (agent-core depends on the JDK only; this also excludes ADK types)
    @Test
    void architecture_coreImportsOnlyJavaPackages_always() {
        classes()
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "dev.ucplab.core..")
                // The module has no classes until M1.
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }
}
