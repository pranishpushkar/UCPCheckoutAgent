package dev.ucplab.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalSecret;
import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.ApprovalTokens;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.InMemoryIntentQueries;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentCursorCodec;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The snapshot an answer in 23.2 shows never carries the session id, while the hash covers the full snapshot, session
 * id included, and the approval is decided by token alone (23.2, 9.2, I8; H5 C1). A guard test: it passed from the
 * start, because {@link QuoteCard} never showed the session id.
 */
class ApprovalAnswerSessionIdTest {

    private static final String CANARY = "CANARY-key-6e1f0b";
    private static final SessionId KEYED = new SessionId("gid://shopify/Checkout/hWN1x2?key=" + CANARY);
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final UserId ALICE = new UserId("alice");
    private static final IntentId ID = new IntentId("intent-1");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicLong seq = new AtomicLong();
    private final InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
    private final InMemoryApprovalRepository approvals = new InMemoryApprovalRepository();
    private final ApprovalTokens tokens = new ApprovalTokens(
            ApprovalSecret.of("a-test-approval-secret-of-32-bytes-or-more".getBytes(StandardCharsets.UTF_8)));
    private final DefaultApprovalService service = new DefaultApprovalService(intents, approvals, tokens, clock,
            () -> "id-" + seq.incrementAndGet(), "worker-a", Duration.ofSeconds(30), Duration.ofMinutes(10),
            Duration.ofMinutes(5));
    private final AuthContext alice = new AuthContext(ALICE, "alice@example.com", NOW, "sess-alice");

    // Covers: 23.2, 9.2, I8, H5 C1
    @Test
    void approvalAnswers_carryNoSessionId_andTheTokenVerifiesAgainstTheFullIdHash() {
        PurchaseIntent intent = PurchaseIntent.proposed(ID, ALICE, new MerchantId("shop-1"), "dedupe",
                        List.of(new ProposedLine("gid://shopify/ProductVariant/1", 1)), "alice@example.com", NOW)
                .withSessionId(KEYED).withState(IntentState.AWAITING_APPROVAL, NOW);
        intents.insert(intent);
        CheckoutView quote = new CheckoutView(KEYED, CheckoutStatus.READY_FOR_COMPLETE, "USD",
                List.of(new LineView("gid://shopify/ProductVariant/1", 1, 69995)), 69995, 0, 69995, Optional.empty(),
                List.of());
        PendingApproval opened = service.open(intent, quote);

        Map<String, Object> pending = new ApprovalController(service, intents, id -> false,
                m -> dev.ucplab.core.merchant.Completion.AGENT).pending(alice, ID.value());
        Map<String, Object> read = new IntentController(intents, approvals, new InMemoryIntentQueries(intents),
                new IntentCursorCodec(new byte[32])).intent(alice, ID.value());
        for (String answer : List.of(CanonicalJson.write(pending), CanonicalJson.write(read))) {
            assertThat(answer).doesNotContain(CANARY).doesNotContain("key=").doesNotContain("hWN1x2")
                    .doesNotContain("session");
        }

        // The stored snapshot keeps the full id, and the hash is over it.
        ApprovalRecord stored = approvals.listForSystem(ID).getFirst();
        assertThat(stored.snapshot().sessionId()).isEqualTo(KEYED);
        assertThat(stored.snapshotHash()).isEqualTo(opened.snapshotHash()).isEqualTo(stored.snapshot().hash());
        ApprovalSnapshot s = stored.snapshot();
        String withoutKey = new ApprovalSnapshot(s.intentId(), s.merchantId(), new SessionId("gid://shopify/Checkout/hWN1x2"),
                s.lines(), s.currency(), s.totalMinor(), s.maxTotalMinor(), s.expiresAt(), s.fulfillment()).hash();
        assertThat(stored.snapshotHash()).isNotEqualTo(withoutKey);
        String token = (String) pending.get("token");
        assertThat(tokens.verify(token, stored.id(), ID, stored.snapshotHash())).isTrue();
        assertThat(tokens.verify(token, stored.id(), ID, withoutKey)).isFalse();

        // The token alone decides: the owner approves with it, never sending a session id.
        assertThat(service.decide(alice, ID, token, true)).isEqualTo(new ApprovalDecision.Approved(ID));
    }
}
