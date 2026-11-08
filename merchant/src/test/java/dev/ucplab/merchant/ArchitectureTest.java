package dev.ucplab.merchant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.merchant");

    // Covers: section 2.2 and 15 (the merchant has zero dependencies and uses the JDK only).
    // com.sun.net.httpserver is the JDK's built-in HTTP server (module jdk.httpserver), used by MerchantServer.
    @Test
    void architecture_merchantImportsOnlyJdkPackages_always() {
        classes()
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "com.sun.net.httpserver..", "dev.ucplab.merchant..")
                .check(PRODUCTION_CLASSES);
    }
}
