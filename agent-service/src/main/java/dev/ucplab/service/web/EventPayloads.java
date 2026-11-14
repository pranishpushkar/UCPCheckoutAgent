package dev.ucplab.service.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The allowlist for event payloads in {@code GET /api/intents/{id}/events} (m6-plan section 3.1). A key on the list
 * keeps its value (and a map or list under it is filtered the same way); every other key's value becomes
 * {@code "[redacted]"}. That covers {@code rationale} (model text) and {@code cause} (free text from the gateway), and
 * any key a future event adds until someone puts it on the list. The keys stay, so the console can show that a field
 * existed.
 */
final class EventPayloads {

    static final String REDACTED = "[redacted]";

    static final Set<String> ALLOWED = Set.of("from", "to", "event", "guard", "stage", "allowed", "rule", "reason",
            "merchant_id", "lines", "item_id", "quantity", "approval_id", "decision", "decided_by", "outcome",
            "http_status", "code", "codes", "status", "approval_status", "hash_matches", "attempt", "probe", "probes",
            "resend", "total_minor", "max_total_minor");

    private EventPayloads() {
    }

    static Map<String, Object> filter(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        payload.forEach((key, value) -> out.put(key, ALLOWED.contains(key) ? filterValue(value) : REDACTED));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object filterValue(Object value) {
        return switch (value) {
            case Map<?, ?> m -> filter((Map<String, Object>) m);
            case List<?> l -> l.stream().map(EventPayloads::filterValue).toList();
            case null -> null;
            default -> value;
        };
    }
}
