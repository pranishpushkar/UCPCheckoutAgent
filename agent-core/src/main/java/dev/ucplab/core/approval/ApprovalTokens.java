package dev.ucplab.core.approval;

import dev.ucplab.core.crypto.HmacSha256;
import dev.ucplab.core.crypto.Sha256;
import dev.ucplab.core.intent.IntentId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/**
 * Approval tokens. Contract 9.2:
 * {@code approvalToken = base64url(HMAC_SHA256(approvalSecret, approvalId + ':' + intentId + ':' + snapshotHash))},
 * base64url without padding. The approval id makes tokens differ between approvals whose snapshots are
 * byte-identical, so a consumed token never verifies against a later approval. Tokens are secrets: they go to the approver channel only and are
 * never logged, put in a tool result or put in a prompt (I6, I8). Single use and the TTL are
 * enforced by the approval service, not here.
 */
public final class ApprovalTokens {

    private final ApprovalSecret secret;

    public ApprovalTokens(ApprovalSecret secret) {
        this.secret = Objects.requireNonNull(secret, "secret");
    }

    public String mint(String approvalId, IntentId intentId, String snapshotHash) {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(snapshotHash, "snapshotHash");
        byte[] message = (approvalId + ':' + intentId.value() + ':' + snapshotHash).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(HmacSha256.compute(secret.key(), message));
    }

    /** Constant-time comparison of a presented token with the expected one (9.2). */
    public boolean verify(String presentedToken, String approvalId, IntentId intentId, String snapshotHash) {
        Objects.requireNonNull(presentedToken, "presentedToken");
        byte[] expected = mint(approvalId, intentId, snapshotHash).getBytes(StandardCharsets.US_ASCII);
        byte[] presented = presentedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }

    /** The value stored in {@code approval.token_hash}; the token itself is never stored (section 10). */
    public static String hashForStorage(String token) {
        Objects.requireNonNull(token, "token");
        return Sha256.hex(token);
    }
}
