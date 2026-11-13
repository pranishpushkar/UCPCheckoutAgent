package dev.ucplab.eval;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.merchant.LabMerchant;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses EVAL_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.eval");

    /** Every production class of this module, LabMerchant included, but none of the merchant module's own. */
    private static final JavaClasses ALL_HARNESS_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> location.contains("eval-harness/target/classes"))
            .importPackages("dev.ucplab.eval", "dev.ucplab.merchant");

    // Covers: section 5.2 (the module-wide import really holds LabMerchant and nothing else of the merchant package)
    @Test
    void architecture_harnessImportHoldsOnlyLabMerchantOfTheMerchantPackage() {
        assertThat(ALL_HARNESS_CLASSES.stream().filter(c -> c.getPackageName().equals("dev.ucplab.merchant"))
                .map(c -> c.getName())).containsExactly(LabMerchant.class.getName());
    }

    // Covers: section 5.2 (only agent-adk may import ADK types)
    @Test
    void architecture_noAdkTypes_outsideAgentAdk() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("com.google.adk..", "com.google.genai..")
                .check(ALL_HARNESS_CLASSES);
    }

    // Covers: section 5.2 (D2: the harness embeds core, ucp, persistence, the proxy and, since M5, agent-runtime; never
    // agent-adk, the service layer or Spring), Decision 1 (eval-harness: no dev.ucplab.adk..)
    @Test
    void architecture_harnessNeverUsesAdkServiceOrSpring() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("dev.ucplab.adk..", "dev.ucplab.service..",
                        "org.springframework..")
                .check(ALL_HARNESS_CLASSES);
    }

    // Covers: section 5.2, A.3 (the harness reaches the merchant module only through LabMerchant, which reads and
    // controls it over HTTP)
    @Test
    void architecture_harnessReachesTheMerchantOnlyThroughLabMerchant() {
        noClasses()
                .should().dependOnClassesThat(resideInAPackage("dev.ucplab.merchant..")
                        .and(not(equivalentTo(LabMerchant.class))))
                .check(EVAL_CLASSES);
    }

    // Covers: I8 (the model key is handed out only in agent-adk's gemini package; nothing here reads it)
    @Test
    void architecture_noCallToModelKeyReveal_always() {
        noClasses()
                .should().callMethod(ModelKey.class, "reveal")
                .check(ALL_HARNESS_CLASSES);
    }
}
