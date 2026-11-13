package dev.ucplab.adk;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
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
                // The module has no production classes until C15 (GeminiModelClient).
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }
}
