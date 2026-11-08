package dev.ucplab.core.approval;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of the {@code approval} table (section 10). Stores a hash of the token, never the token (9.2).
 * {@code owner} must equal the intent's owner (I10); {@code decidedBy} records who decided (T3).
 */
public record ApprovalRecord(
        String id,
        IntentId intentId,
        UserId owner,
        ApprovalSnapshot snapshot,
        String snapshotHash,
        String tokenHash,
        Instant expiresAt,
        ApprovalStatus status,
        Optional<Instant> decidedAt,
        Optional<UserId> decidedBy) {

    public ApprovalRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(snapshotHash, "snapshotHash");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(decidedBy, "decidedBy");
        if (!snapshot.intentId().equals(intentId)) {
            throw new IllegalArgumentException("snapshot belongs to another intent");
        }
    }

    ApprovalRecord withStatus(ApprovalStatus newStatus, Optional<Instant> at, Optional<UserId> by) {
        return new ApprovalRecord(id, intentId, owner, snapshot, snapshotHash, tokenHash, expiresAt, newStatus, at, by);
    }
}
