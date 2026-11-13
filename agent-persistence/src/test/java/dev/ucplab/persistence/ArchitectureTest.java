package dev.ucplab.persistence;

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
            .importPackages("dev.ucplab.persistence");

    // Covers: section 5.2 (only agent-adk may import ADK types)
    @Test
    void architecture_noAdkTypes_outsideAgentAdk() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("com.google.adk..", "com.google.genai..")
                // The module has no classes yet.
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    // Covers: section 5.2 (agent-persistence may depend on agent-core, the JDK, Flyway and the SQLite driver only)
    @Test
    void architecture_persistenceDependsOnCoreAndJdbcOnly_always() {
        classes()
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax.sql..", "dev.ucplab.core..", "dev.ucplab.persistence..",
                        "org.flywaydb..", "org.sqlite..")
                // The module has no classes yet.
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
