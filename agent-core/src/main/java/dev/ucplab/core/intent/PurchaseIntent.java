package dev.ucplab.core.intent;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One purchase and its durable state, mirroring the {@code purchase_intent} table (section 10).
 * {@code owner} is the signed-in user who proposed it and never changes (T1, T2). {@code version} is
 * the optimistic-locking counter (7.8); {@code leaseOwner} and {@code leaseUntil} are the worker lease (7.6).
 */
public record PurchaseIntent(
        IntentId id,
        UserId owner,
        MerchantId merchantId,
        String dedupeKey,
        IntentState state,
        long version,
        Optional<SessionId> sessionId,
        Optional<String> approvedSnapshotHash,
        int requoteCount,
        Optional<String> orderId,
        Optional<String> failureReason,
        Optional<Instant> uncertainSince,
        Optional<String> leaseOwner,
        Optional<Instant> leaseUntil,
        Instant createdAt,
        Instant updatedAt) {

    public PurchaseIntent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(approvedSnapshotHash, "approvedSnapshotHash");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(failureReason, "failureReason");
        Objects.requireNonNull(uncertainSince, "uncertainSince");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (dedupeKey.isBlank()) {
            throw new IllegalArgumentException("dedupeKey must not be blank");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative: " + version);
        }
        if (requoteCount < 0) {
            throw new IllegalArgumentException("requoteCount must not be negative: " + requoteCount);
        }
    }

    /** A new intent in PROPOSED at version 0, with no session, no lease and no outcome. */
    public static PurchaseIntent proposed(
            IntentId id, UserId owner, MerchantId merchantId, String dedupeKey, Instant now) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, IntentState.PROPOSED, 0,
                Optional.empty(), Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), now, now);
    }

    public PurchaseIntent withState(IntentState newState, Instant now) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, newState, version, sessionId, approvedSnapshotHash,
                requoteCount, orderId, failureReason, uncertainSince, leaseOwner, leaseUntil, createdAt, now);
    }

    public PurchaseIntent withSessionId(SessionId session) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, state, version, Optional.of(session), approvedSnapshotHash,
                requoteCount, orderId, failureReason, uncertainSince, leaseOwner, leaseUntil, createdAt, updatedAt);
    }

    public PurchaseIntent withVersion(long newVersion) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, state, newVersion, sessionId, approvedSnapshotHash,
                requoteCount, orderId, failureReason, uncertainSince, leaseOwner, leaseUntil, createdAt, updatedAt);
    }

    public PurchaseIntent withLease(Optional<String> newLeaseOwner, Optional<Instant> newLeaseUntil) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, state, version, sessionId, approvedSnapshotHash,
                requoteCount, orderId, failureReason, uncertainSince, newLeaseOwner, newLeaseUntil, createdAt,
                updatedAt);
    }
}
