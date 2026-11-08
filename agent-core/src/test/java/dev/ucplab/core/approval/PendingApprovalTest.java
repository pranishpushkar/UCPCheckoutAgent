package dev.ucplab.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PendingApprovalTest {

    // Covers: I8, 9.2 (the approval token never appears in toString, which may reach logs)
    @Test
    void toString_omitsToken() {
        ApprovalSnapshot snapshot = new ApprovalSnapshot(
                new IntentId("i1"), new MerchantId("shop"), new SessionId("s1"),
                List.of(new SnapshotLine("sku", 1, 500)), "USD", 500, 500, Instant.parse("2026-01-01T00:10:00Z"));

        PendingApproval pending = new PendingApproval(snapshot, snapshot.hash(), "super-secret-token");

        assertThat(pending.toString()).doesNotContain("super-secret-token").contains("i1");
    }

    // Covers: I7, I8 (the frozen complete body holds a credential and is not printed)
    @Test
    void frozenBodyToString_omitsContent() {
        FrozenBody body = new FrozenBody("{\"token\":\"tok_ok\"}");

        assertThat(body.toString()).doesNotContain("tok_ok");
        assertThat(body.bytes()).isEqualTo("{\"token\":\"tok_ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
