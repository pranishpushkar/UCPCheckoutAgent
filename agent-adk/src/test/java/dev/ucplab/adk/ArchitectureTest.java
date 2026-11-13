package dev.ucplab.adk;

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
            .importPackages("dev.ucplab.adk");

    // Covers: section 5.2 (agent-adk is the one module that may import Gemini SDK types; beyond them it uses the JDK and
    // core only, so the SDK's own dependencies (Jackson, OkHttp, Guava) never reach our code). The other modules' rules
    // forbid com.google.adk and com.google.genai.
    @Test
    void architecture_adkUsesOnlyJdkCoreAndGenai_always() {
        classes()
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax.crypto..", "dev.ucplab.core..", "dev.ucplab.adk..",
                        "com.google.genai..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: I8 (only the gemini package reads the model key, to hand it to the SDK's client builder)
    @Test
    void architecture_modelKeyRevealedOnlyInTheGeminiPackage_always() {
        noClasses()
                .that().resideOutsideOfPackage("dev.ucplab.adk.gemini..")
                .should().callMethod(ModelKey.class, "reveal")
                // Every class of the module is in the gemini package today; the rule guards any added beside it.
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }
}
