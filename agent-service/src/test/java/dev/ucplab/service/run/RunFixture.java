package dev.ucplab.service.run;

import dev.ucplab.core.agent.InMemoryRunRepository;
import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.runtime.AgentBudget;
import dev.ucplab.runtime.AgentRuntime;
import dev.ucplab.runtime.ToolPorts;
import dev.ucplab.service.FakeClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A supervisor over fake models, an in-memory run repository and a fake clock (m6-plan C13). The runtime's ports are
 * stubs that no test model reaches: the models here only call {@code ask_user}.
 */
final class RunFixture {

    static final Instant T0 = Instant.parse("2026-11-14T09:00:00Z");
    static final AuthContext ALICE = auth("alice");
    static final AuthContext BOB = auth("bob");

    final FakeClock clock = new FakeClock(T0);
    final InMemoryRunRepository runs = new InMemoryRunRepository(clock);
    final InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);

    static AuthContext auth(String user) {
        return new AuthContext(new UserId(user), user + "@example.com", T0, "sid-" + user);
    }

    static ModelTurn text(String text) {
        return new ModelTurn(text, List.of(), Usage.NONE);
    }

    static ModelTurn ask(String id, String question) {
        return new ModelTurn("", List.of(new ToolCall(id, "ask_user", Map.of("question", question))), Usage.NONE);
    }

    AgentRuntime runtime(ModelClient model) {
        return new AgentRuntime(model, "scripted", ports(), runs, AgentBudget.DEFAULT, Duration.ofMinutes(5), clock);
    }

    RunSupervisor supervisor(ModelClient model, RunSupervisorConfig config) {
        return supervisor(model, config, AnswerWait.REAL);
    }

    RunSupervisor supervisor(ModelClient model, RunSupervisorConfig config, AnswerWait wait) {
        return new RunSupervisor(runtime(model), runs, intents, clock, new SequentialIdGenerator("run-"), config, wait);
    }

    /** A supervisor over another runtime but the same stores: what a restart leaves behind. */
    RunSupervisor restarted(ModelClient model, RunSupervisorConfig config) {
        return supervisor(model, config);
    }

    static RunSupervisorConfig config(Duration responseWait) {
        RunSupervisorConfig d = RunSupervisorConfig.defaults();
        return new RunSupervisorConfig(responseWait, d.askTimeout(), d.idle(), d.maxLive(), d.maxPerHour(),
                d.maxTurns());
    }

    /** The text of the last user message the model has seen. */
    static String lastUserText(Conversation c) {
        return ((dev.ucplab.core.agent.Message.User) c.messages().stream()
                .filter(m -> m instanceof dev.ucplab.core.agent.Message.User).reduce((a, b) -> b).orElseThrow()).text();
    }

    private static ToolPorts ports() {
        MerchantRegistry registry = new MerchantRegistry() {
            @Override
            public List<MerchantEntry> active() {
                return List.of();
            }

            @Override
            public Optional<MerchantEntry> find(MerchantId id) {
                return Optional.empty();
            }

            @Override
            public MerchantProfile profile(MerchantId id) {
                throw new UnsupportedOperationException();
            }
        };
        Orchestrator orchestrator = new Orchestrator() {
            @Override
            public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines,
                                          String rationale) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StepResult advance(IntentId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public IntentStatus status(AuthContext auth, IntentId id) {
                throw new UnsupportedOperationException();
            }
        };
        return new ToolPorts(registry, Map.of(), orchestrator);
    }
}
