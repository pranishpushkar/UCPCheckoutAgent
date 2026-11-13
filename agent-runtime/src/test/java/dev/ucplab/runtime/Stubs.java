package dev.ucplab.runtime;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentNotFoundException;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Money;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** In-process stand-ins for the tool ports, for tests that need no merchant. */
final class Stubs {

    static final MerchantId SHOP = new MerchantId("shop-1");
    static final MerchantEntry ENTRY = new MerchantEntry(SHOP, URI.create("http://127.0.0.1:1"), "Stub shop",
            MerchantStatus.ACTIVE);
    static final AuthContext ALICE = new AuthContext(new UserId("alice"), "alice@example.com",
            Instant.parse("2026-01-01T00:00:00Z"), "sess-alice");
    static final ToolContext CONTEXT = new ToolContext(ALICE, new RunId("run-1"));

    private Stubs() {
    }

    static CatalogProduct product(String id, String title, String description, long price) {
        return new CatalogProduct(id, title, Optional.of(description), List.of(new CatalogVariant(id, title,
                new Money(price, "USD"), new Availability(true, Optional.of("in_stock")))));
    }

    static MerchantRegistry registry(List<MerchantEntry> active) {
        return new MerchantRegistry() {
            @Override
            public List<MerchantEntry> active() {
                return active;
            }

            @Override
            public Optional<MerchantEntry> find(MerchantId id) {
                return active.stream().filter(m -> m.id().equals(id)).findFirst();
            }

            @Override
            public MerchantProfile profile(MerchantId id) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** A catalog that answers every search with {@code products} and every product call through {@code product}. */
    static CatalogGateway catalog(List<CatalogProduct> products,
                                  Function<String, CallOutcome<CatalogProduct>> product) {
        return new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                return new CallOutcome.Ok<>(200, new CatalogPage(products, false), List.of());
            }

            @Override
            public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CallOutcome<CatalogProduct> product(String id) {
                return product.apply(id);
            }
        };
    }

    static CallOutcome<CatalogProduct> notFound() {
        return new CallOutcome.ProtocolError<>(200, "not_found", "", Optional.empty());
    }

    /** An orchestrator that records every call and answers from fixed values. */
    static class RecordingOrchestrator implements Orchestrator {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<UserId> users = new CopyOnWriteArrayList<>();
        ProposalResult proposal = new ProposalResult(new IntentId("intent-1"), IntentState.REJECTED, Optional.empty());
        Map<IntentId, IntentStatus> statuses = Map.of(new IntentId("intent-1"), new IntentStatus(
                new IntentId("intent-1"), IntentState.REJECTED, Optional.empty(), Optional.of("policy_p4")));
        UserId owner = ALICE.user();

        @Override
        public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines,
                                      String rationale) {
            calls.add("propose " + merchant + " " + new ArrayList<>(lines) + " " + rationale);
            users.add(auth.user());
            return proposal;
        }

        @Override
        public StepResult advance(IntentId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IntentStatus status(AuthContext auth, IntentId id) {
            calls.add("status " + id);
            users.add(auth.user());
            IntentStatus s = statuses.get(id);
            if (s == null || !auth.user().equals(owner)) {
                throw new IntentNotFoundException("no such intent: " + id);
            }
            return s;
        }
    }

    static ToolPorts ports(CatalogGateway catalog, Orchestrator orchestrator) {
        return new ToolPorts(registry(List.of(ENTRY)), Map.of(SHOP, catalog), orchestrator);
    }
}
