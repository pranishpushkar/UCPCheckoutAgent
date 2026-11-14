package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.Migrations;
import dev.ucplab.persistence.SqlRunRepository;
import dev.ucplab.runtime.AgentBudget;
import dev.ucplab.runtime.AgentRuntime;
import dev.ucplab.runtime.RunOutcome;
import dev.ucplab.runtime.ScriptedModel;
import dev.ucplab.runtime.ToolPorts;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * I8 for stored runs (section 10 V5, 23.1, m6-plan section 5): a run driven through the real {@link AgentRuntime} into
 * the real {@link SqlRunRepository} leaves none of its text in the database file. A canary goes into every string that
 * could reach the repository: the user message, a tool argument, the {@code ask_user} question and answer, the model's
 * final text, a tool's exception message, a model exception message, the email and the session id. {@code RunRecord}
 * has no field for any of them; this scans the bytes on disk to prove it end to end. It lives in eval-harness because
 * this is the module that already depends on both agent-runtime and agent-persistence.
 */
class RunStoreNoTextTest {

    private static final String CANARY = "CANARY-I8-" + Long.toHexString(Double.doubleToLongBits(Math.random()));
    private static final MerchantId SHOP = new MerchantId("shop-1");
    private static final MerchantEntry ENTRY =
            new MerchantEntry(SHOP, URI.create("http://127.0.0.1:1"), "Shop", MerchantStatus.ACTIVE);

    @TempDir
    Path dir;

    private static String canary(String where) {
        return CANARY + "-" + where;
    }

    /** Every file SQLite wrote for the database (the file, and any -wal, -shm or -journal), as one byte string. */
    private static String bytesOnDisk(Path dir) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                all.append(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1));
            }
        }
        return all.toString();
    }

    private static final MerchantRegistry REGISTRY = new MerchantRegistry() {
        @Override
        public List<MerchantEntry> active() {
            return List.of(ENTRY);
        }

        @Override
        public Optional<MerchantEntry> find(MerchantId id) {
            return id.equals(SHOP) ? Optional.of(ENTRY) : Optional.empty();
        }

        @Override
        public MerchantProfile profile(MerchantId id) {
            throw new UnsupportedOperationException();
        }
    };

    /** A catalog that finds nothing, or throws with a canary in its message. */
    private static CatalogGateway catalog(boolean throwing) {
        return new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                if (throwing) {
                    throw new IllegalStateException(canary("tool-exception") + " " + request.query());
                }
                return new CallOutcome.Ok<>(200, new CatalogPage(List.of(), false), List.of());
            }

            @Override
            public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CallOutcome<CatalogProduct> product(String id) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final Orchestrator NO_ORCHESTRATOR = new Orchestrator() {
        @Override
        public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String why) {
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

    private static ScriptedModel script(String turns) {
        return ScriptedModel.parse("{\"name\":\"i8\",\"turns\":[" + turns + "]}");
    }

    private static AuthContext alice() {
        return new AuthContext(new UserId("alice"), canary("email") + "@example.com", Instant.now(),
                canary("session-id"));
    }

    // Covers: I8 (positive control: the scan finds a canary written into a column that does store text)
    @Test
    void scan_findsACanaryInAStoredColumn() throws IOException {
        Path file = dir.resolve("control.db");
        Migrations.migrate(file);
        try (Database db = Database.open(file, 1)) {
            new SqlRunRepository(db, Clock.systemUTC()).insert(new UserId("alice"),
                    RunRecord.started(new RunId("r-control"), new UserId("alice"), canary("model-name"), Instant.now()));
        }

        assertThat(bytesOnDisk(dir)).contains(canary("model-name"));
    }

    // Covers: I8, 23.1, section 10 (runs that carried a canary in every text the runtime handles leave none of it in the
    // database file: done after a search and an ask_user, failed on a tool exception, failed on a model exception)
    @Test
    void storedRuns_holdNoText() throws IOException {
        Path file = dir.resolve("runs.db");
        Migrations.migrate(file);
        List<String> outcomes = new ArrayList<>();
        try (Database db = Database.open(file, 2)) {
            SqlRunRepository runs = new SqlRunRepository(db, Clock.systemUTC());

            ScriptedModel talking = script("""
                    {"call": [{"tool": "search_catalog", "args": {"query": "%s", "max_results": 1}}]},
                    {"call": [{"tool": "ask_user", "args": {"question": "%s"}}]},
                    {"text": "%s"}""".formatted(canary("tool-argument"), canary("ask-question"),
                    canary("model-text")));
            RunOutcome done = new AgentRuntime(talking, "scripted", new ToolPorts(REGISTRY, Map.of(SHOP, catalog(false)),
                    NO_ORCHESTRATOR), runs, AgentBudget.DEFAULT, Duration.ofMinutes(5), Clock.systemUTC())
                    .run(alice(), new RunId("r-done"), canary("user-message"),
                            (question, timeout) -> Optional.of(canary("ask-answer")));
            outcomes.add(done.getClass().getSimpleName());

            ScriptedModel searching = script("""
                    {"call": [{"tool": "search_catalog", "args": {"query": "%s", "max_results": 1}}]},
                    {"text": "%s"}""".formatted(canary("tool-argument-2"), canary("model-text-2")));
            RunOutcome toolFailed = new AgentRuntime(searching, "scripted", new ToolPorts(REGISTRY,
                    Map.of(SHOP, catalog(true)), NO_ORCHESTRATOR), runs, AgentBudget.DEFAULT, Duration.ofMinutes(5),
                    Clock.systemUTC()).run(alice(), new RunId("r-tool"), canary("user-message-2"), (q, t) -> Optional.empty());
            outcomes.add(toolFailed.getClass().getSimpleName());

            RunOutcome modelFailed = new AgentRuntime((conversation, tools) -> {
                throw new IllegalStateException(canary("model-exception"));
            }, "scripted", new ToolPorts(REGISTRY, Map.of(SHOP, catalog(false)), NO_ORCHESTRATOR), runs,
                    AgentBudget.DEFAULT, Duration.ofMinutes(5), Clock.systemUTC())
                    .run(alice(), new RunId("r-model"), canary("user-message-3"), (q, t) -> Optional.empty());
            outcomes.add(modelFailed.getClass().getSimpleName());

            UserId owner = new UserId("alice");
            assertThat(runs.find(owner, new RunId("r-done"))).hasValueSatisfying(r -> {
                assertThat(r.outcome()).contains("done");
                assertThat(r.toolEvents()).extracting(e -> e.tool()).containsExactly("search_catalog", "ask_user");
            });
            assertThat(runs.find(owner, new RunId("r-tool"))).as("the tool's failure is in the record as a code")
                    .hasValueSatisfying(r -> {
                        assertThat(r.outcome()).contains("failed");
                        assertThat(r.reason()).contains("tool_error");
                    });
            assertThat(runs.find(owner, new RunId("r-model"))).hasValueSatisfying(r -> {
                assertThat(r.outcome()).contains("failed");
                assertThat(r.reason()).contains("model_error");
            });
        }

        assertThat(outcomes).containsExactly("Done", "Failed", "Failed");
        assertThat(bytesOnDisk(dir)).as("no canary in the database files").doesNotContain(CANARY);
    }
}
