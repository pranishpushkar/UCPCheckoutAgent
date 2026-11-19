package dev.ucplab.adk;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ucplab.adk.gemini.GeminiModelClient;
import dev.ucplab.core.agent.ModelKey;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.adk");

    // Covers: section 5.2 (agent-adk is the one module that may import Gemini SDK types; beyond them it uses the JDK and
    // core only, so the SDK's own dependencies (Jackson, OkHttp, Guava) never reach our code). The other modules' rules
    // forbid com.google.adk and com.google.genai. 5.2's one exception, GeminiModelClient's OkHttp client, has its own
    // rule below; every other class stays as before.
    @Test
    void architecture_adkUsesOnlyJdkCoreAndGenai_always() {
        classes()
                .that().doNotHaveFullyQualifiedName(GeminiModelClient.class.getName())
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax.crypto..", "dev.ucplab.core..", "dev.ucplab.adk..",
                        "com.google.genai..")
                .check(PRODUCTION_CLASSES);
    }

    /** 5.2, 6.3: what GeminiModelClient may use of OkHttp, to build the SDK's HTTP client and read Retry-After. */
    private static final java.util.Set<String> OKHTTP_ALLOWED = java.util.Set.of("okhttp3.OkHttpClient",
            "okhttp3.OkHttpClient$Builder", "okhttp3.Interceptor", "okhttp3.Interceptor$Chain", "okhttp3.Request",
            "okhttp3.Response");

    // Covers: section 5.2, 6.3 (GeminiModelClient uses the JDK, core, genai and only the six OkHttp types it needs for
    // the Retry-After interceptor: no other OkHttp, Okio, Jackson or Guava type)
    @Test
    void architecture_geminiModelClient_usesOnlyItsSixOkHttpTypes_always() {
        classes()
                .that().haveFullyQualifiedName(GeminiModelClient.class.getName())
                .should().onlyDependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "the JDK, core, adk, genai or one of " + OKHTTP_ALLOWED, c ->
                                OKHTTP_ALLOWED.contains(c.getName())
                                        || c.getPackageName().startsWith("java.")
                                        || c.getPackageName().startsWith("javax.crypto")
                                        || c.getPackageName().startsWith("dev.ucplab.core")
                                        || c.getPackageName().startsWith("dev.ucplab.adk")
                                        || c.getPackageName().startsWith("com.google.genai")))
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

    // Covers: section 5.2 (only agent-service may import Spring, Jakarta Servlet or Jackson 3 types; m6-plan 2.2)
    @Test
    void architecture_noSpringTypes_outsideAgentService() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.servlet..", "tools.jackson..")
                .check(PRODUCTION_CLASSES);
    }
}
