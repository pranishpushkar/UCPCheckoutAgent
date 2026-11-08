package dev.ucplab.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.IntentId;
import org.junit.jupiter.api.Test;

class ApprovalTokensTest {

    private static final ApprovalSecret SECRET = ApprovalSecret.of("test-approval-secret-0123456789abcdef");
    private static final IntentId INTENT = new IntentId("intent-1");
    private static final String APPROVAL = "approval-1";
    private static final String HASH = "37bd4b1e6bfbdb0fde5ee59cf333a620a3ab239b63d2f574c241a56cadb228fa";
    /**
     * From Python: base64.urlsafe_b64encode(hmac.new(secret, b'approval-1:intent-1:' + hash, sha256)), padding
     * stripped. (The formula without the approval id gives 0pHFbSz-HCsnnW1i9BJhsscsqlvr-i3f5X4cFHrltpM.)
     */
    private static final String GOLDEN_TOKEN = "Z6dAOKttfzueuwAG0Qj9v67_MFkeQ9JnKv3rInBzbvY";
    /** The same intent and snapshot hash under approval-2, from the same Python expression. */
    private static final String GOLDEN_TOKEN_APPROVAL_2 = "xpexmS5FHgs6idPkghiXeuHoJbujYWp3uz8iEXn_ok4";

    private final ApprovalTokens tokens = new ApprovalTokens(SECRET);

    // Covers: 9.2 (token formula with the approval id, base64url without padding)
    @Test
    void approvalTokens_mint_matchesGoldenValue() {
        assertThat(tokens.mint(APPROVAL, INTENT, HASH)).isEqualTo(GOLDEN_TOKEN).matches("[A-Za-z0-9_-]{43}");
    }

    // Covers: 9.2, I3 (a valid token verifies only for its own approval, intent and snapshot hash)
    @Test
    void approvalTokens_verify_acceptsOnlyMatchingIntentAndHash() {
        String token = tokens.mint(APPROVAL, INTENT, HASH);

        assertThat(tokens.verify(token, APPROVAL, INTENT, HASH)).isTrue();
        assertThat(tokens.verify(token, APPROVAL, new IntentId("intent-2"), HASH)).isFalse();
        assertThat(tokens.verify(token, APPROVAL, INTENT, HASH.replace('3', '4'))).isFalse();
        assertThat(tokens.verify(token, "approval-2", INTENT, HASH)).isFalse();
    }

    // Covers: 9.2 "Snapshot and token details", I3 (two approvals with byte-identical snapshots get different
    // tokens, so the first's token never verifies against the second)
    @Test
    void approvalTokens_mint_differsBetweenApprovals_forIdenticalSnapshot() {
        String first = tokens.mint(APPROVAL, INTENT, HASH);
        String second = tokens.mint("approval-2", INTENT, HASH);

        assertThat(second).isEqualTo(GOLDEN_TOKEN_APPROVAL_2).isNotEqualTo(first);
        assertThat(tokens.verify(first, "approval-2", INTENT, HASH)).isFalse();
        assertThat(tokens.verify(second, "approval-2", INTENT, HASH)).isTrue();
    }

    // Covers: 9.2, I3 (tampered, truncated, extended, padded or empty tokens are refused)
    @Test
    void approvalTokens_verify_rejectsAlteredTokens() {
        String token = tokens.mint(APPROVAL, INTENT, HASH);
        char last = token.charAt(token.length() - 1);
        String flipped = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThat(tokens.verify(flipped, APPROVAL, INTENT, HASH)).isFalse();
        assertThat(tokens.verify(token.substring(1), APPROVAL, INTENT, HASH)).isFalse();
        assertThat(tokens.verify(token + "A", APPROVAL, INTENT, HASH)).isFalse();
        assertThat(tokens.verify(token + "=", APPROVAL, INTENT, HASH)).isFalse();
        assertThat(tokens.verify("", APPROVAL, INTENT, HASH)).isFalse();
    }

    // Covers: 9.2 (the token is bound to the server's secret)
    @Test
    void approvalTokens_verify_rejectsTokenMintedWithOtherSecret() {
        String forged = new ApprovalTokens(ApprovalSecret.of("another-approval-secret-0123456789abcdef")).mint(APPROVAL, INTENT, HASH);

        assertThat(tokens.verify(forged, APPROVAL, INTENT, HASH)).isFalse();
    }

    // Covers: section 10 (token_hash: store a hash of the token, never the token). Golden value from Python hashlib.
    @Test
    void approvalTokens_hashForStorage_isSha256HexOfToken() {
        assertThat(ApprovalTokens.hashForStorage(GOLDEN_TOKEN))
                .isEqualTo("844b766731cbe5266a8ab9f675228acc977638bffa17b48f504620e2c5c78e34")
                .doesNotContain(GOLDEN_TOKEN);
    }

    // Covers: I8 (the secret never appears in its string form)
    @Test
    void approvalSecret_toString_isRedacted() {
        assertThat(SECRET.toString()).doesNotContain("test-approval-secret").contains("redacted");
    }

    // Covers: 9.2 (approvalSecret must be at least 32 bytes; 31 is refused, 32 accepted)
    @Test
    void approvalSecret_throws_whenShorterThan32Bytes() {
        assertThatThrownBy(() -> ApprovalSecret.of("x".repeat(31))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalSecret.of(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThat(ApprovalSecret.of("x".repeat(32))).isNotNull();
        assertThat(ApprovalSecret.of(new byte[32])).isNotNull();
    }

    // Covers: 9.2 (length is counted in UTF-8 bytes, not characters)
    @Test
    void approvalSecret_countsUtf8Bytes_notChars() {
        // 16 characters, 32 bytes in UTF-8.
        assertThat(ApprovalSecret.of("é".repeat(16))).isNotNull();
    }

    // Covers: 9.2 (the prod profile refuses the .env.example placeholder)
    @Test
    void approvalSecret_ofProduction_refusesChangeMePlaceholder() {
        assertThatThrownBy(() -> ApprovalSecret.ofProduction("change-me-local-only-at-least-32-bytes"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalSecret.ofProduction("x".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ApprovalSecret.ofProduction("a-real-production-secret-0123456789")).isNotNull();
    }

    // Covers: 9.2 (the local placeholder is long enough for non-prod profiles)
    @Test
    void approvalSecret_of_acceptsLocalPlaceholder() {
        assertThat(ApprovalSecret.of("change-me-local-only-at-least-32-bytes")).isNotNull();
    }
}
