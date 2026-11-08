package dev.ucplab.core.intent;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One purchase and its durable state, mirroring the {@code purchase_intent} table (section 10).
 * {@code owner} is the signed-in user who proposed it and never changes (T1, T2). {@code version} is
 * the optimistic-locking counter (7.8); {@code leaseOwner} and {@code leaseUntil} are the worker lease (7.6).
 *
 * <p>{@code lines} and {@code buyerEmail} are the proposal itself, fixed at creation. Section 10 has no column for
 * them, but a create or update resent after a crash must be rebuilt byte for byte (A.1, 409 on a different body), so
 * they are stored with the intent ({@code lines_json}, {@code buyer_email} in the SQL adapter). The buyer email comes
 * from {@code AuthContext}, never from the model (18.1); it is personal data, so {@link #toString()} leaves it out.
 */
public record PurchaseIntent(
        IntentId id,
        UserId owner,
        MerchantId merchantId,
        String dedupeKey,
        List<ProposedLine> lines,
        String buyerEmail,
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
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        Objects.requireNonNull(buyerEmail, "buyerEmail");
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
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an intent needs at least one line");
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
            IntentId id, UserId owner, MerchantId merchantId, String dedupeKey, List<ProposedLine> lines,
            String buyerEmail, Instant now) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, lines, buyerEmail, IntentState.PROPOSED, 0,
                Optional.empty(), Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), now, now);
    }

    public PurchaseIntent withState(IntentState newState, Instant now) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, lines, buyerEmail, newState, version, sessionId,
                approvedSnapshotHash, requoteCount, orderId, failureReason, uncertainSince, leaseOwner, leaseUntil,
                createdAt, now);
    }

    public PurchaseIntent withSessionId(SessionId session) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, lines, buyerEmail, state, version, Optional.of(session),
                approvedSnapshotHash, requoteCount, orderId, failureReason, uncertainSince, leaseOwner, leaseUntil,
                createdAt, updatedAt);
    }

    public PurchaseIntent withApprovedSnapshotHash(Optional<String> hash) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, lines, buyerEmail, state, version, sessionId, hash, requoteCount,
                orderId, failureReason, uncertainSince, leaseOwner, leaseUntil, createdAt, updatedAt);
    }

    public PurchaseIntent withRequoteCount(int newRequoteCount) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, lines, buyerEmail, state, version, sessionId, approvedSnapshotHash,
                newRequoteCount, orderId, failureReason, uncertainSince, leaseOwner, leaseUntil, createdAt, updatedAt);
    }

    public PurchaseIntent withOrderId(String newOrderId) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, lines, buyerEmail, state, version, sessionId, approvedSnapshotHash,
                requoteCount, Optional.of(newOrderId), failureReason, uncertainSince, leaseOwner, leaseUntil,
                createdAt, updatedAt);
    }

    public PurchaseIntent withFailureReason(String reason) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, lines, buyerEmail, state, version, sessionId, approvedSnapshotHash,
                requoteCount, orderId, Optional.of(reason), uncertainSince, leaseOwner, leaseUntil, createdAt,
                updatedAt);
    }

    public PurchaseIntent withUncertainSince(Instant since) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, lines, buyerEmail, state, version, sessionId, approvedSnapshotHash,
                requoteCount, orderId, failureReason, Optional.of(since), leaseOwner, leaseUntil, createdAt,
                updatedAt);
    }

    public PurchaseIntent withVersion(long newVersion) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, lines, buyerEmail, state, newVersion, sessionId,
                approvedSnapshotHash, requoteCount, orderId, failureReason, uncertainSince, leaseOwner, leaseUntil,
                createdAt, updatedAt);
    }

    public PurchaseIntent withLease(Optional<String> newLeaseOwner, Optional<Instant> newLeaseUntil) {
        return new PurchaseIntent(
                id, owner, merchantId, dedupeKey, lines, buyerEmail, state, version, sessionId,
                approvedSnapshotHash, requoteCount, orderId, failureReason, uncertainSince, newLeaseOwner,
                newLeaseUntil, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "PurchaseIntent[id=" + id + ", owner=" + owner + ", merchantId=" + merchantId + ", state=" + state
                + ", version=" + version + ", sessionId=" + sessionId + ", requoteCount=" + requoteCount
                + ", orderId=" + orderId + ", failureReason=" + failureReason + ", leaseOwner=" + leaseOwner + ']';
    }
}
