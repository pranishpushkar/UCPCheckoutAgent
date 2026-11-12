package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.Migrations;
import dev.ucplab.persistence.SqlApprovalRepository;
import dev.ucplab.persistence.SqlIntentRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 7.8 "never hold a lock across network I/O": the orchestrator over the SQL repositories gives every connection back
 * before it calls the merchant. The gateway wrapper reads {@link Database#inUse()} at each call.
 */
class SqlNoConnectionAcrossMerchantCallTest {

    @TempDir
    Path dir;

    private final Lab lab = new Lab();
    private Database db;
    private final List<String> calls = new ArrayList<>();
    private final List<Integer> inUseDuringCalls = new ArrayList<>();

    /** Records how many connections are checked out while each merchant call runs. */
    private final class Watching implements MerchantGateway {
        private final MerchantGateway delegate = lab.merchant;

        private void seen(String call) {
            calls.add(call);
            inUseDuringCalls.add(db.inUse());
        }

        @Override
        public MerchantProfile discover(MerchantId merchant) {
            seen("discover");
            return delegate.discover(merchant);
        }

        @Override
        public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
            seen("create");
            return delegate.create(request, key);
        }

        @Override
        public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
            seen("update");
            return delegate.update(id, request, key);
        }

        @Override
        public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
            seen("complete");
            return delegate.complete(id, body, key);
        }

        @Override
        public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
            seen("cancel");
            return delegate.cancel(id, key);
        }

        @Override
        public CallOutcome<CheckoutView> get(SessionId id) {
            seen("get");
            return delegate.get(id);
        }
    }

    @BeforeEach
    void open() {
        Path file = dir.resolve("lab.db");
        Migrations.migrate(file);
        db = Database.open(file, 4);
    }

    @AfterEach
    void close() {
        db.close();
    }

    // Covers: 7.8 (no connection is checked out during any merchant call, through propose, approve, complete and a
    // reconciliation after a lost response, and the one order is made)
    @Test
    void noConnectionIsHeld_duringAnyMerchantCall() {
        SqlIntentRepository intents = new SqlIntentRepository(db, lab.clock);
        SqlApprovalRepository approvals = new SqlApprovalRepository(db);
        Lab.Worker w = lab.worker("worker-a", new Watching(), approvals, intents);
        lab.merchant.addRule(dev.ucplab.core.merchant.fake.FaultRule.nth(
                dev.ucplab.core.merchant.fake.MerchantCall.COMPLETE, 1, new dev.ucplab.core.merchant.fake.Fault.NotSent()));

        IntentId id = lab.propose(w, Lab.TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        IntentState last = lab.drive(w, id);

        assertThat(last).isEqualTo(IntentState.COMPLETED);
        assertThat(lab.merchant.orders()).hasSize(1);
        assertThat(calls).contains("create", "complete", "get");
        assertThat(inUseDuringCalls).as("connections in use during " + calls).isNotEmpty().containsOnly(0);
        assertThat(db.inUse()).isZero();
    }
}
