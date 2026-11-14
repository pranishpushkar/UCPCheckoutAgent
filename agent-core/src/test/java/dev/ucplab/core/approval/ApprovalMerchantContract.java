package dev.ucplab.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An approval's snapshot names the merchant of its intent (9.2, I11), over a history that holds two merchants: run
 * against the in-memory and the SQL adapters, which supply the two repositories.
 */
public abstract class ApprovalMerchantContract {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final MerchantId A = new MerchantId("merchant-a");
    private static final MerchantId B = new MerchantId("merchant-b");

    protected final MutableClock clock = new MutableClock(T0);
    protected IntentRepository intents;
    protected ApprovalRepository approvals;

    /** Sets {@link #intents} and {@link #approvals} to fresh, empty repositories reading time from {@code clock}. */
    protected abstract void create(Clock clock);

    @BeforeEach
    void createRepositories() {
        create(clock);
    }

    private PurchaseIntent quoting(String id, String user, MerchantId merchant) {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId(id), new UserId(user), merchant, "key-" + id,
                List.of(new ProposedLine("item_123", 1)), user + "@example.com", clock.instant())
                .withState(IntentState.QUOTING, clock.instant());
        intents.insert(intent);
        return intent;
    }

    // Covers: I11, 9.2, 19 (every approval snapshot, however many times an intent is re-quoted, names the merchant of
    // the intent it belongs to, across intents of two merchants and two users)
    @Test
    void everySnapshot_namesTheMerchantOfItsIntent() {
        DefaultApprovalService service = new DefaultApprovalService(intents, approvals,
                new ApprovalTokens(ApprovalSecret.of("0123456789abcdef0123456789abcdef")), clock,
                new SequentialIdGenerator("ap"), "worker-1", Duration.ofSeconds(30), Duration.ofMinutes(10),
                Duration.ofMinutes(15));
        List<PurchaseIntent> all = List.of(quoting("i1", "alice", A), quoting("i2", "alice", B),
                quoting("i3", "bob", A), quoting("i4", "bob", B));
        CheckoutView quote = new CheckoutView(new SessionId("chk_1"), CheckoutStatus.READY_FOR_COMPLETE, "USD",
                List.of(new LineView("item_123", 1, 2500)), 2700, 200, 2700, Optional.empty(), List.of());

        for (PurchaseIntent intent : all) {
            service.open(intent, quote);
        }
        service.open(all.get(1), quote);   // a re-quote of the intent at merchant B

        for (PurchaseIntent intent : all) {
            List<ApprovalRecord> history = approvals.listForSystem(intent.id());
            assertThat(history).isNotEmpty();
            assertThat(history).allSatisfy(a -> assertThat(a.snapshot().merchantId()).isEqualTo(intent.merchantId()));
            assertThat(approvals.findLatest(intent.owner(), intent.id()).orElseThrow().snapshot().merchantId())
                    .isEqualTo(intent.merchantId());
        }
        assertThat(approvals.listForSystem(all.get(1).id())).hasSize(2);
    }
}
