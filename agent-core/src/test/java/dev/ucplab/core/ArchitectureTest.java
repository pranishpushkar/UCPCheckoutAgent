package dev.ucplab.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ucplab.core.agent.ModelKey;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.core");

    // Covers: section 5.2 (agent-core depends on java.* and javax.crypto.* only; this also excludes ADK types)
    @Test
    void architecture_coreImportsOnlyJavaPackages_always() {
        classes()
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax.crypto..", "dev.ucplab.core..")
                // The module has no classes until M1.
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    // Covers: I8 (the model key is handed out only in agent-adk's gemini package; nothing here reads it)
    @Test
    void architecture_noCallToModelKeyReveal_always() {
        noClasses()
                .should().callMethod(ModelKey.class, "reveal")
                .check(PRODUCTION_CLASSES);
    }
}
