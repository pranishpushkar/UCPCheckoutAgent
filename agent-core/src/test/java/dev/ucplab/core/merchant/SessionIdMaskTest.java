package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A merchant checkout id may carry an access key; only the part before {@code ?} is ever printed (I8; H5 C1). */
class SessionIdMaskTest {

    private static final String CANARY = "CANARY-key-5d2e9a";
    private static final SessionId KEYED = new SessionId("gid://shopify/Checkout/hWN1x2?key=" + CANARY);

    // Covers: I8, 10 (session_id masked wherever shown), H5 C1
    @Test
    void keyedSessionId_printsNothingFromTheQueryOn() {
        assertThat(KEYED.toString()).isEqualTo("gid://shopify/Checkout/hWN1x2?[redacted]");
        assertThat(String.valueOf(Optional.of(KEYED))).doesNotContain(CANARY).doesNotContain("key=");
        assertThat(KEYED.value()).endsWith(CANARY);
    }

    // Covers: I8, H5 C1 (a lab id has no query part and prints as it is)
    @Test
    void labSessionId_printsAsItIs() {
        assertThat(new SessionId("cs_0001").toString()).isEqualTo("cs_0001");
        assertThat(new SessionId("a?").toString()).isEqualTo("a?[redacted]");
    }

    // Covers: I8, 10, H5 C1 (an intent's toString shows the session id masked)
    @Test
    void intentToString_masksTheSessionKey() {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId("intent-1"), new UserId("alice"),
                new MerchantId("shop-1"), "dedupe", List.of(new ProposedLine("item_1", 1)), "alice@example.com",
                Instant.parse("2026-11-16T00:00:00Z")).withSessionId(KEYED);

        assertThat(intent.toString()).contains("gid://shopify/Checkout/hWN1x2?[redacted]").doesNotContain(CANARY);
    }
}
