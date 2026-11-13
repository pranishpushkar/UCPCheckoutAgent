package dev.ucplab.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.core.agent.RunRepository;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentQueries;
import dev.ucplab.core.intent.IntentRepository;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

    // Covers: section 5.2 (only agent-service may import Spring, Jakarta Servlet or Jackson 3 types; m6-plan 2.2)
    @Test
    void architecture_noSpringTypes_outsideAgentService() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.servlet..", "tools.jackson..")
                .check(PRODUCTION_CLASSES);
    }

    /**
     * The methods of the tenancy ports that take no {@link UserId}, and who calls each (T2, 18.1). Anything else on
     * these ports must take the owner's id. A new system method means a new line here, with its caller.
     */
    private static final Map<String, String> WITHOUT_OWNER = Map.ofEntries(
            // IntentRepository: the orchestrator's step function and RecoveryWorker (7.3, 7.6), the harness.
            Map.entry("IntentRepository.findForSystem(IntentId)", "RecoveryWorker, the orchestrator, the eval harness"),
            Map.entry("IntentRepository.findActiveByDedupeKey(String)", "DefaultOrchestrator.propose, de-duplication (7.7)"),
            Map.entry("IntentRepository.insert(PurchaseIntent)", "DefaultOrchestrator.propose; the owner is the intent's"),
            Map.entry("IntentRepository.update(PurchaseIntent,long,String)", "the orchestrator's fenced writes (7.6)"),
            Map.entry("IntentRepository.appendEvent(IntentId,IntentEvent)", "the orchestrator and the approval service"),
            Map.entry("IntentRepository.recordAttempt(MerchantAttempt,PurchaseIntent,long,String)",
                    "the orchestrator's write-ahead (7.3 step 3)"),
            Map.entry("IntentRepository.resolveAttempt(AttemptId,AttemptStatus,Optional,Optional)",
                    "the orchestrator (7.3 step 5)"),
            Map.entry("IntentRepository.resolveAttempt(AttemptId,AttemptStatus,Optional,Optional,PurchaseIntent,long,"
                    + "String)", "the orchestrator (7.3 step 5, fenced)"),
            Map.entry("IntentRepository.resolveAttempts(IntentId,List,AttemptStatus,Optional,Optional,long,String)",
                    "the orchestrator's reconciliation (7.5, 7.6)"),
            Map.entry("IntentRepository.attempts(IntentId)", "the orchestrator's step function (7.3 step 1, 7.4)"),
            Map.entry("IntentRepository.claim(IntentId,String,Duration)",
                    "propose, ApprovalService.decide, advance, RecoveryWorker.driveNow (7.6)"),
            Map.entry("IntentRepository.claimRecoverable(String,Duration,int)", "RecoveryWorker (7.6)"),
            Map.entry("IntentRepository.claimRecoverable(String,Duration,int,Set)", "RecoveryWorker (7.6)"),
            Map.entry("IntentRepository.renewLease(IntentId,String,Duration)", "RecoveryWorker (7.6)"),
            // ApprovalRepository: the approval service, the orchestrator, RecoveryWorker and the harness.
            Map.entry("ApprovalRepository.insert(ApprovalRecord)", "ApprovalService.open; the owner is the record's"),
            Map.entry("ApprovalRepository.findLatestForSystem(IntentId)", "the orchestrator, RecoveryWorker (7.6)"),
            Map.entry("ApprovalRepository.listForSystem(IntentId)", "the eval harness (S16)"),
            Map.entry("ApprovalRepository.transition(String,ApprovalStatus,ApprovalStatus,Instant,Optional)",
                    "ApprovalService.decide, the orchestrator, RecoveryWorker (9.2 compare-and-set)"),
            // RunRepository: the harness only.
            Map.entry("RunRepository.findForSystem(RunId)", "the eval harness (T2)"));

    private static final Set<Class<?>> TENANCY_PORTS =
            Set.of(IntentRepository.class, ApprovalRepository.class, RunRepository.class, IntentQueries.class);

    /** {@code Port.method(SimpleType,...)}, the allowlist's key; overloads differ by their parameter types. */
    private static String key(JavaMethod m) {
        return m.getOwner().getSimpleName() + "." + m.getName() + "("
                + m.getRawParameterTypes().stream().map(JavaClass::getSimpleName).collect(Collectors.joining(","))
                + ")";
    }

    private static final ArchCondition<JavaMethod> TAKE_A_USER_ID =
            new ArchCondition<>("take the owner's UserId, or be on the named allowlist") {
                @Override
                public void check(JavaMethod m, ConditionEvents events) {
                    boolean owner = m.getRawParameterTypes().stream().anyMatch(t -> t.isEquivalentTo(UserId.class));
                    boolean allowed = WITHOUT_OWNER.containsKey(key(m));
                    events.add(new SimpleConditionEvent(m, owner || allowed,
                            key(m) + (owner ? " takes a UserId" : allowed ? " is allowlisted" : " takes no UserId")));
                }
            };

    // Covers: T2, 18.1 (every method of the intent, approval and run ports takes the owner's UserId, except the named
    // system methods; m6-plan section 12)
    @Test
    void architecture_tenancyPortMethods_takeAUserId() {
        methods().that().areDeclaredInClassesThat(new DescribedPredicate<JavaClass>("a tenancy port") {
                    @Override
                    public boolean test(JavaClass c) {
                        return TENANCY_PORTS.stream().anyMatch(c::isEquivalentTo);
                    }
                })
                .should(TAKE_A_USER_ID)
                .check(PRODUCTION_CLASSES);
    }

    // Covers: T2 (the allowlist names only methods that exist, so a removed or renamed method cannot leave a stale
    // exception behind)
    @Test
    void tenancyAllowlist_namesOnlyExistingMethods() {
        Set<String> declared = TENANCY_PORTS.stream()
                .flatMap(p -> PRODUCTION_CLASSES.get(p).getMethods().stream())
                .map(ArchitectureTest::key)
                .collect(Collectors.toSet());

        org.assertj.core.api.Assertions.assertThat(declared).containsAll(WITHOUT_OWNER.keySet());
    }
}
