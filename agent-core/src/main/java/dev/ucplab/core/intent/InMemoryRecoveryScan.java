package dev.ucplab.core.intent;

import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.merchant.MerchantId;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** {@link RecoveryScan} and {@link InFlightMerchants} over the in-memory repositories. */
public final class InMemoryRecoveryScan implements RecoveryScan, InFlightMerchants {

    private final InMemoryIntentRepository intents;
    private final ApprovalRepository approvals;

    public InMemoryRecoveryScan(InMemoryIntentRepository intents, ApprovalRepository approvals) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    @Override
    public List<IntentId> approvalsDue(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        Comparator<Due> order = Comparator.comparing(Due::expiresAt).thenComparing(d -> d.id().value());
        return intents.inState(IntentState.AWAITING_APPROVAL).stream()
                .filter(i -> i.leaseUntil().map(t -> t.isBefore(now)).orElse(true))
                .flatMap(i -> approvals.findLatestForSystem(i.id()).stream()
                        .filter(a -> a.status() == ApprovalStatus.EXPIRED
                                || (a.status() == ApprovalStatus.PENDING && !now.isBefore(a.expiresAt())))
                        .map(a -> new Due(i.id(), a.expiresAt())))
                .sorted(order)
                .limit(limit)
                .map(Due::id)
                .toList();
    }

    @Override
    public Set<MerchantId> merchantsInFlight() {
        Set<MerchantId> out = new HashSet<>();
        for (IntentState state : IntentState.values()) {
            if (!state.isTerminal()) {
                intents.inState(state).forEach(i -> out.add(i.merchantId()));
            }
        }
        return Set.copyOf(out);
    }

    private record Due(IntentId id, Instant expiresAt) {
    }
}
