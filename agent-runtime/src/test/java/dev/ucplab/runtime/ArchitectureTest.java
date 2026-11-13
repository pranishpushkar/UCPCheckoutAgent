package dev.ucplab.runtime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.core.approval.ApprovalService;
import dev.ucplab.core.merchant.MerchantGateway;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    // Covers: 6.2, I6 (the classes implementing Tool are exactly the five tools)
    @Test
    void architecture_toolImplementations_areExactlyTheFive() {
        Set<String> tools = PRODUCTION_CLASSES.stream()
                .filter(c -> c.isAssignableTo(Tool.class) && !c.isInterface())
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(tools).containsExactlyInAnyOrder("SearchCatalogTool", "GetProductTool", "ProposePurchaseTool",
                "GetPurchaseStatusTool", "AskUserTool");
    }

    // Covers: I6, 9.3 L1 (nothing in agent-runtime approves, completes or cancels)
    @Test
    void architecture_runtimeNeverDecidesCompletesOrCancels_always() {
        noClasses()
                .should().callMethodWhere(target(ApprovalService.class, "decide"))
                .orShould().callMethodWhere(target(MerchantGateway.class, "complete"))
                .orShould().callMethodWhere(target(MerchantGateway.class, "cancel"))
                .check(PRODUCTION_CLASSES);
    }

    // Covers: 7.8 (the loop takes no lock of any kind, so none can be held across a model or merchant call)
    @Test
    void architecture_runtimeUsesNoLocks_always() {
        noClasses()
                .should().dependOnClassesThat().resideInAPackage("java.util.concurrent.locks..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: 7.8 (no synchronized block or method anywhere in agent-runtime's main source)
    @Test
    void source_hasNoSynchronized_always() throws IOException {
        Pattern word = Pattern.compile("\\bsynchronized\\b");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }

        assertThat(sources).isNotEmpty();
        for (Path source : sources) {
            assertThat(word.matcher(Files.readString(source)).find()).as(source.toString()).isFalse();
        }
    }

    private static DescribedPredicate<JavaMethodCall> target(Class<?> owner, String method) {
        return DescribedPredicate.describe(owner.getSimpleName() + "." + method,
                call -> call.getTargetOwner().isAssignableTo(owner) && call.getName().equals(method));
    }

    // Covers: I8 (the model key is handed out only in agent-adk's gemini package; nothing here reads it)
    @Test
    void architecture_noCallToModelKeyReveal_always() {
        noClasses()
                .should().callMethod(ModelKey.class, "reveal")
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
