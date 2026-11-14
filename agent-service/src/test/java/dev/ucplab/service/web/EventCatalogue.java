package dev.ucplab.service.web;

import java.util.Map;
import java.util.Set;

/**
 * The catalogue of intent event types and the keys each carries (m6-plan section 3.1), shared by
 * {@link EventPayloadsTest}, which checks it against the source, and the API test, which checks the events a real
 * purchase writes against it.
 */
public final class EventCatalogue {

    private EventCatalogue() {
    }

    /** Each type the code writes, with the top-level keys of its payload (nested keys in {@link #NESTED}). */
    public static final Map<String, Set<String>> KEYS = Map.ofEntries(
            Map.entry("proposed", Set.of("merchant_id", "lines", "rationale")),
            Map.entry("policy_decision", Set.of("stage", "allowed", "rule", "reason")),
            Map.entry("transition", Set.of("from", "event", "guard", "to")),
            Map.entry("approval_opened", Set.of("approval_id", "snapshot_hash", "total_minor", "max_total_minor")),
            Map.entry("approval_decided", Set.of("approval_id", "decision", "decided_by")),
            Map.entry("approval_decision_recovered", Set.of("approval_id")),
            Map.entry("approval_invalid", Set.of("approval_status", "hash_matches")),
            Map.entry("resend_not_approved_here", Set.of("attempt_id")),
            Map.entry("complete_resent", Set.of("attempt_id", "resend")),
            Map.entry("reconcile_read", Set.of("outcome", "status")),
            Map.entry("reconcile_wait", Set.of("reason")),
            Map.entry("escalated", Set.of("attempts")),
            Map.entry("cancel_sent", Set.of("outcome", "attempt")),
            Map.entry("cancel_gave_up", Set.of("attempts")),
            Map.entry("orphan_session_found", Set.of("probe")),
            Map.entry("orphan_probe_no_session", Set.of("probe")),
            Map.entry("orphan_probe_sent", Set.of("outcome", "probe")),
            Map.entry("orphan_probe_gave_up", Set.of("probes")),
            Map.entry("orphan_probe_no_create", Set.of()),
            Map.entry("orphan_probe_body_mismatch", Set.of("attempt_id")));

    /** Keys inside a nested value: a proposal's lines. ({@code escalated}'s attempt list is redacted whole.) */
    public static final Set<String> NESTED = Set.of("item_id", "quantity");

    /**
     * Keys deliberately kept out of the console: model text, internal ids and hashes, and the attempt list. The counters
     * and the approval's amounts are allowed (Stage 3 review, point 4). Redacted by the allowlist's default.
     */
    public static final Set<String> REDACTED = Set.of("rationale", "cause", "snapshot_hash", "attempt_id",
            "attempts");
}
