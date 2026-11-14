package dev.ucplab.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The event payload allowlist (m6-plan section 3.1, I8). The event types are read from agent-core's source, not
 * listed by hand, and each must appear in {@link EventCatalogue#KEYS}, the catalogue of the keys each type carries.
 * Every catalogued key is either on the allowlist or deliberately redacted, so a new type or a new key fails here until
 * someone decides whether the console may show it.
 */
class EventPayloadsTest {

    /** Where intent events are written: the orchestrator and the approval service. */
    private static final List<Path> WRITERS = List.of(
            Path.of("../agent-core/src/main/java/dev/ucplab/core/orchestrator/DefaultOrchestrator.java"),
            Path.of("../agent-core/src/main/java/dev/ucplab/core/approval/DefaultApprovalService.java"));

    /** The three ways a type is written: {@code event(id, "t", ...)}, {@code IntentEvent.of("t", ...)}, and the
     * orchestrator's {@code stopProbing(intent, "t", ...)}. */
    private static final List<Pattern> TYPE_LITERALS = List.of(
            Pattern.compile("\\bevent\\(\\s*[\\w.()]+,\\s*\"([a-z_]+)\""),
            Pattern.compile("IntentEvent\\.of\\(\\s*\"([a-z_]+)\""),
            Pattern.compile("stopProbing\\(\\s*\\w+,\\s*\"([a-z_]+)\""));

    /**
     * A write of an event whose type is a variable, which the literal scan would miss. Only the two pass-throughs whose
     * callers the literal scan already covers are allowed: {@code event(id, type, ...)} hands its {@code type} to
     * {@code IntentEvent.of}, and {@code stopProbing} hands its {@code eventType} to {@code event}.
     */
    private static final Pattern NON_LITERAL_TYPE = Pattern.compile(
            "\\bevent\\(\\s*[\\w.()]+,\\s*([a-zA-Z]\\w*)|IntentEvent\\.of\\(\\s*([a-zA-Z]\\w*)");
    private static final Set<String> PASS_THROUGHS = Set.of("type", "eventType");

    private static Set<String> typesInCode() throws IOException {
        Set<String> types = new TreeSet<>();
        for (Path file : WRITERS) {
            String source = Files.readString(file);
            assertThat(NON_LITERAL_TYPE.matcher(source).results()
                    .map(m -> m.group(1) != null ? m.group(1) : m.group(2)).filter(v -> !PASS_THROUGHS.contains(v)))
                    .as("an event written with a computed type in %s", file).isEmpty();
            for (Pattern p : TYPE_LITERALS) {
                p.matcher(source).results().map(m -> m.group(1)).forEach(types::add);
            }
        }
        return types;
    }

    // Covers: I8, m6-plan section 3.1 (every event type the orchestrator and the approval service write, read from
    // their source, is in the catalogue, and the catalogue names no type the code does not write)
    @Test
    void catalogue_coversEveryTypeInTheCode() throws IOException {
        assertThat(typesInCode()).isNotEmpty().containsExactlyInAnyOrderElementsOf(EventCatalogue.KEYS.keySet());
    }

    // Covers: I8, m6-plan section 3.1 (every catalogued key is decided: on the allowlist, or deliberately redacted)
    @Test
    void catalogue_everyKeyIsAllowedOrRedacted() {
        Set<String> all = new TreeSet<>(EventCatalogue.NESTED);
        EventCatalogue.KEYS.values().forEach(all::addAll);

        for (String key : all) {
            assertThat(EventPayloads.ALLOWED.contains(key) ^ EventCatalogue.REDACTED.contains(key)).as(key).isTrue();
        }
    }

    // Covers: I8 (a planted rationale and cause read back as "[redacted]"; allowed keys and nested lines pass)
    @Test
    void filter_redactsRationaleAndCause_keepsAllowedKeys() {
        Map<String, Object> filtered = EventPayloads.filter(Map.of(
                "merchant_id", "merchant-a",
                "lines", List.of(Map.of("item_id", "item_123", "quantity", 2L, "note", "CANARY-nested")),
                "rationale", "CANARY-model-text",
                "cause", "CANARY-gateway-text"));

        assertThat(filtered).containsEntry("merchant_id", "merchant-a")
                .containsEntry("rationale", EventPayloads.REDACTED).containsEntry("cause", EventPayloads.REDACTED)
                .containsEntry("lines", List.of(Map.of("item_id", "item_123", "quantity", 2L,
                        "note", EventPayloads.REDACTED)));
        assertThat(filtered.toString()).doesNotContain("CANARY");
    }

    // Covers: I8 (a key no one has classified is redacted by default, whatever its value's shape)
    @Test
    void filter_unknownKey_isRedactedWhole() {
        Map<String, Object> filtered = EventPayloads.filter(Map.of("new_field", Map.of("reason", "CANARY"),
                "attempts", List.of(Map.of("attempt_id", "a-1"))));

        assertThat(filtered).containsOnly(Map.entry("new_field", EventPayloads.REDACTED),
                Map.entry("attempts", EventPayloads.REDACTED));
    }
}
