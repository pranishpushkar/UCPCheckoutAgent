package dev.ucplab.eval.interop;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.eval.AgentStack;
import dev.ucplab.eval.MerchantWiring;
import dev.ucplab.eval.Scenarios;
import dev.ucplab.eval.TimeScale;
import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.faultproxy.RuleSet;
import dev.ucplab.persistence.Migrations;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * The rows whose wire is recorded and compared between the lab merchant and the sample (H1 C9b): S0 (no fault), S5 (the price
 * changes after the quote), S6 (sold out) and S7 (declined). One run of each row per merchant, through a fault proxy with no
 * rules, with a {@link RecordingTransport} on the agent's checkout gateway.
 */
final class WireRows {

    static final List<String> ROWS = List.of("S0", "S5", "S6", "S7");

    /** A merchant the rows can be run against. */
    record Target(String name, URI base, MerchantWiring wiring, List<ProposedLine> basket, String ok, String decline,
                  LongSupplier price, LongConsumer setPrice, IntSupplier stock, IntConsumer setStock) {
    }

    private WireRows() {
    }

    /** Runs {@code row} once against {@code target}; returns what the agent's transport saw. */
    static List<WireStep> record(String row, Target target, Path work, TimeScale scale) throws Exception {
        try (FaultProxy proxy = FaultProxy.start(target.base(), 0, InteropRows.key())) {
            proxy.install(RuleSet.empty());
            Path db = work.resolve("wire-" + target.name() + "-" + row + ".db");
            Migrations.migrate(db);
            long price = target.price().getAsLong();
            int stock = target.stock().getAsInt();
            java.util.List<RecordingTransport> recorders = new java.util.concurrent.CopyOnWriteArrayList<>();
            MerchantWiring wiring = target.wiring().withTransport(t -> {
                RecordingTransport r = new RecordingTransport(t);
                recorders.add(r);
                return r;
            });
            String token = row.equals("S7") ? target.decline() : target.ok();
            try (AgentStack stack = new AgentStack(proxy.baseUrl(), db, scale, 1, new SequentialIdGenerator("w" + row), token,
                    scale.orchestrator().lease(), "", wiring)) {
                AuthContext auth = new AuthContext(new UserId("wire-user"), "john.doe@example.com", stack.clock.instant(), "wire");
                IntentId id = stack.orchestrator.propose(auth, Scenarios.SHOP, target.basket(), "wire " + row).intentId();
                boolean changed = false;
                Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
                while (Instant.now().isBefore(deadline)) {
                    StepResult r = stack.orchestrator.advance(id);
                    if (r.after().isTerminal()) {
                        break;
                    }
                    if (r.after() == IntentState.AWAITING_APPROVAL && r.idle()) {
                        var pending = stack.approvalService.pending(auth, id).orElseThrow();
                        if (!changed) {
                            if (row.equals("S5")) {
                                target.setPrice().accept(price + 200);
                            } else if (row.equals("S6")) {
                                target.setStock().accept(0);
                            }
                            changed = true;
                        }
                        stack.approvalService.decide(auth, id, pending.token(), true);
                        continue;
                    }
                    Thread.sleep(50);
                }
            } finally {
                target.setPrice().accept(price);
                target.setStock().accept(stock);
            }
            return recorders.stream().flatMap(r -> r.steps().stream()).toList();
        }
    }
}
