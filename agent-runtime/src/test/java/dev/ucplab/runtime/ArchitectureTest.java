package dev.ucplab.runtime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.runtime");

    // Covers: section 5.2, D1 (agent-runtime uses the JDK and core only)
    @Test
    void architecture_runtimeUsesOnlyJdkAndCore_always() {
        classes()
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "dev.ucplab.core..", "dev.ucplab.runtime..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: section 5.2, D1 (named, so a failure says why: no Google SDK, JSON library or reactive types)
    @Test
    void architecture_noGoogleJacksonOrReactiveTypes_inRuntime() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.google..", "com.fasterxml..", "io.reactivex..", "reactor..")
                .check(PRODUCTION_CLASSES);
    }
}
