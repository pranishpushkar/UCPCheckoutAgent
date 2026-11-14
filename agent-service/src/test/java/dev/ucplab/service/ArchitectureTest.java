package dev.ucplab.service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.core.agent.RunRepository;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.persistence.SqlIntentRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * agent-service's rules (5.2, m6-plan section 2.2). The rules for the CLI package (C19) and the lab package's profile
 * (C15) arrive with those packages.
 */
class ArchitectureTest {

    /** The one class that reads the environment, so {@code GEMINI_API_KEY} has one way in (I8, C9). */
    private static final String ENVIRONMENT = "dev.ucplab.service.config.Environment";
    /** The one class that may take the system clock and ids directly; everything else is given them (7.8, C9). */
    private static final String STACK_CONFIGURATION = "dev.ucplab.service.config.StackConfiguration";
    /** The one class that builds an AuthContext, from the session row (T1, m6-plan section 4, C10). */
    private static final String SESSION_INTERCEPTOR = "dev.ucplab.service.web.SessionInterceptor";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.ucplab.service");

    // Covers: section 5.2 (only agent-adk may import ADK or Gemini SDK types; agent-adk itself is runtime scope here)
    @Test
    void architecture_noAdkTypes_outsideAgentAdk() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("com.google.adk..", "com.google.genai..",
                        "dev.ucplab.adk..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: I8 (the model key is handed out only in agent-adk's gemini package; nothing here reads it)
    @Test
    void architecture_noCallToModelKeyReveal_always() {
        noClasses()
                .should().callMethod(ModelKey.class, "reveal")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: T2, T6, I9 (service code calls only owner-scoped repository methods; the system reads are for
    // RecoveryWorker, the orchestrator's step and the eval harness, which are not service classes)
    @Test
    void architecture_noSystemRepositoryReads_inService() {
        noClasses()
                .should().callMethodWhere(target(IntentRepository.class,
                        Set.of("findForSystem", "attempts", "claim", "claimRecoverable")))
                .orShould().callMethodWhere(target(ApprovalRepository.class,
                        Set.of("findLatestForSystem", "listForSystem")))
                .orShould().callMethodWhere(target(RunRepository.class, Set.of("findForSystem")))
                .orShould().callMethodWhere(target(SqlIntentRepository.class, Set.of("events")))
                .check(PRODUCTION_CLASSES);
    }

    // Covers: section 20 (no cache of any kind: no Spring cache, no ETag filter) and I8 (no actuator, so no /env)
    @Test
    void architecture_noCachingOrActuator_always() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.cache..",
                        "org.springframework.boot.actuate..")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.filter.ShallowEtagHeaderFilter")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: I8 (the environment, and so GEMINI_API_KEY, is read by one class only)
    @Test
    void architecture_environmentReadByOneClass_only() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(ENVIRONMENT)
                .should().callMethod(System.class, "getenv", String.class)
                .orShould().callMethod(System.class, "getenv")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: 7.8 (Clock and IdGenerator are injected; only the wiring takes the system clock or a random UUID)
    @Test
    void architecture_noSystemClockOrRandomIds_outsideTheWiring() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(STACK_CONFIGURATION)
                .should().callMethod(Instant.class, "now")
                .orShould().callMethod(Clock.class, "systemUTC")
                .orShould().callMethod(UUID.class, "randomUUID")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: T1, m6-plan section 4 (the user id reaches core only through the AuthContext the session interceptor builds
    // from the session row; no other service class constructs one)
    @Test
    void architecture_authContextBuiltBySessionInterceptor_only() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(SESSION_INTERCEPTOR)
                .should().callConstructor(AuthContext.class, UserId.class, String.class, Instant.class, String.class)
                .check(PRODUCTION_CLASSES);
    }

    // Covers: T1 (the rule has something to guard: the interceptor does build one)
    @Test
    void sessionInterceptor_buildsTheAuthContext() {
        assertThat(PRODUCTION_CLASSES.get(SESSION_INTERCEPTOR).getConstructorCallsFromSelf())
                .anyMatch(call -> call.getTargetOwner().isEquivalentTo(AuthContext.class));
    }

    // Covers: 18.5, Stage 3 review point 3 (session and CSRF tokens come from a SecureRandom by type: the sign-in
    // controller takes one, and nothing else random)
    @Test
    void devSignInController_takesASecureRandom() {
        var controller = PRODUCTION_CLASSES.get("dev.ucplab.service.web.DevSignInController");

        assertThat(controller.getConstructors()).hasSize(1);
        assertThat(controller.getConstructors().iterator().next().getRawParameterTypes())
                .anyMatch(t -> t.isEquivalentTo(SecureRandom.class))
                .noneMatch(t -> t.getName().equals("java.util.random.RandomGenerator"));
    }

    // Covers: 7.8 (the run supervisor holds no lock: it moves a run's phase by compare-and-set, so nothing is held across a
    // model or merchant call; the source scan below covers synchronized)
    @Test
    void runSupervisor_usesNoLocks() {
        noClasses()
                .that().resideInAPackage("dev.ucplab.service.run..")
                .should().dependOnClassesThat().resideInAnyPackage("java.util.concurrent.locks..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: 8, m6-plan section 8 (the command line client uses the JDK and agent-core's LenientJson only, so
    // scripts/ucplab runs it from two classes folders without Spring)
    @Test
    void cli_usesTheJdkAndLenientJsonOnly() {
        classes()
                .that().resideInAPackage("dev.ucplab.service.cli..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "dev.ucplab.service.cli..",
                        "dev.ucplab.core.json..")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: 20, m6-plan section 11 (nothing in the service caches: no Spring cache abstraction, no ETag filter, no
    // static resource handler for the API; section 20 says checkout sessions, quotes, approvals and intents are never cached)
    @Test
    void nothingCaches_noCacheAbstraction_noEtagFilter() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.cache..",
                        "org.springframework.boot.cache..", "org.springframework.boot.autoconfigure.cache..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.web.filter.ShallowEtagHeaderFilter")
                .check(PRODUCTION_CLASSES);
    }

    // Covers: 23.3, m6-plan section 6 (every top-level class in the lab package is a local-profile bean, so in prod the routes are
    // not mapped and answer 404)
    @Test
    void labClasses_areAllLocalProfile() {
        classes()
                .that().resideInAPackage("dev.ucplab.service.lab..").and().areTopLevelClasses()
                .should().beAnnotatedWith(org.springframework.context.annotation.Profile.class)
                .andShould(new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaClass>(
                        "have @Profile(\"local\")") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaClass item,
                                      com.tngtech.archunit.lang.ConditionEvents events) {
                        boolean local = item.isAnnotatedWith(org.springframework.context.annotation.Profile.class)
                                && java.util.Arrays.equals(item.getAnnotationOfType(
                                        org.springframework.context.annotation.Profile.class).value(),
                                        new String[] {"local"});
                        events.add(new com.tngtech.archunit.lang.SimpleConditionEvent(item, local,
                                item.getName() + (local ? " is" : " is not") + " @Profile(\"local\")"));
                    }
                })
                .allowEmptyShould(false)
                .check(PRODUCTION_CLASSES);
    }

    // Covers: 7.8 (no synchronized block or method anywhere in agent-service's main source)
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

    private static DescribedPredicate<JavaMethodCall> target(Class<?> owner, Set<String> methods) {
        return DescribedPredicate.describe(owner.getSimpleName() + "." + methods,
                call -> call.getTargetOwner().isAssignableTo(owner) && methods.contains(call.getName()));
    }
}
