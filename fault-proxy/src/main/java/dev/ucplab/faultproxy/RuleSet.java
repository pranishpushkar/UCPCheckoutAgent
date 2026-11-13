package dev.ucplab.faultproxy;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A complete rule set (§12), replaced as a whole through the admin API. Rule ids are unique. Parsing is strict: an
 * unknown or mistyped field is refused rather than ignored, so a typo can never silently turn a fault off.
 */
public record RuleSet(long seed, List<Rule> rules) {

    public RuleSet {
        rules = List.copyOf(rules);
        Set<String> ids = new HashSet<>();
        for (Rule rule : rules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("duplicate rule id: " + rule.id());
            }
        }
    }

    public static RuleSet empty() {
        return new RuleSet(0, List.of());
    }

    /** @throws IllegalArgumentException if the JSON is malformed or is not a valid rule set */
    public static RuleSet parse(String json) {
        Map<String, Object> root = Json.readObject(json);
        allowOnly(root, "rule set", "seed", "rules");
        long seed = requiredLong(root, "seed", "rule set");
        List<?> list = required(root, "rules", List.class, "rule set");
        return new RuleSet(seed, list.stream().map(RuleSet::rule).toList());
    }

    private static Rule rule(Object value) {
        Map<String, Object> rule = object(value, "rule");
        allowOnly(rule, "rule", "id", "match", "action", "times");
        String id = required(rule, "id", String.class, "rule");
        String where = "rule \"" + id + "\"";
        Match match = match(object(required(rule, "match", Map.class, where), where + " match"), where);
        Action action = action(object(required(rule, "action", Map.class, where), where + " action"), where);
        return new Rule(id, match, action, optionalInt(rule, "times", where));
    }

    private static Match match(Map<String, Object> m, String where) {
        allowOnly(m, where + " match", "method", "pathPattern", "nth", "probability");
        Optional<String> method = optional(m, "method", String.class, where);
        Optional<PathPattern> path = optional(m, "pathPattern", String.class, where).map(PathPattern::new);
        OptionalDouble probability = OptionalDouble.empty();
        if (m.containsKey("probability")) {
            if (!(m.get("probability") instanceof Number n)) {
                throw new IllegalArgumentException(where + ": probability must be a number");
            }
            probability = OptionalDouble.of(n.doubleValue());
        }
        return new Match(method, path, optionalInt(m, "nth", where), probability);
    }

    private static Action action(Map<String, Object> a, String where) {
        String type = required(a, "type", String.class, where + " action");
        return switch (type) {
            case "delay" -> {
                allowOnly(a, where + " action", "type", "ms");
                yield new Action.Delay(requiredLong(a, "ms", where));
            }
            case "fail_before_forward" -> {
                allowOnly(a, where + " action", "type", "status", "close", "retryAfterSeconds");
                boolean close = optional(a, "close", Boolean.class, where).orElse(false);
                if (a.containsKey("close") && !close) {
                    throw new IllegalArgumentException(where + ": close, when given, must be true");
                }
                yield new Action.FailBeforeForward(optionalInt(a, "status", where), close,
                        optionalInt(a, "retryAfterSeconds", where));
            }
            case "forward_then_drop_response" -> {
                allowOnly(a, where + " action", "type");
                yield new Action.ForwardThenDropResponse();
            }
            case "forward_then_delay_response" -> {
                allowOnly(a, where + " action", "type", "ms");
                yield new Action.ForwardThenDelayResponse(requiredLong(a, "ms", where));
            }
            case "duplicate_request" -> {
                allowOnly(a, where + " action", "type", "mode");
                String mode = optional(a, "mode", String.class, where).orElse("parallel");
                yield new Action.DuplicateRequest(switch (mode) {
                    case "parallel", "sequential" -> Action.DuplicateRequest.Mode.valueOf(mode.toUpperCase(Locale.ROOT));
                    default -> throw new IllegalArgumentException(where + ": unknown duplicate_request mode " + mode);
                });
            }
            case "corrupt_response" -> {
                allowOnly(a, where + " action", "type", "kind");
                String kind = required(a, "kind", String.class, where);
                yield new Action.CorruptResponse(switch (kind) {
                    case "truncate", "garble" -> Action.CorruptResponse.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
                    default -> throw new IllegalArgumentException(where + ": unknown corrupt_response kind " + kind);
                });
            }
            default -> throw new IllegalArgumentException(where + ": unknown action type " + type);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(what + " must be a JSON object");
        }
        return (Map<String, Object>) map;
    }

    private static void allowOnly(Map<String, Object> map, String what, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (String key : map.keySet()) {
            if (!names.contains(key)) {
                throw new IllegalArgumentException(what + ": unknown field \"" + key + "\"");
            }
        }
    }

    private static <T> T required(Map<String, Object> map, String key, Class<T> type, String where) {
        return optional(map, key, type, where)
                .orElseThrow(() -> new IllegalArgumentException(where + ": missing field \"" + key + "\""));
    }

    private static <T> Optional<T> optional(Map<String, Object> map, String key, Class<T> type, String where) {
        if (!map.containsKey(key)) {
            return Optional.empty();
        }
        Object value = map.get(key);
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(where + ": field \"" + key + "\" must be a " + type.getSimpleName());
        }
        return Optional.of(type.cast(value));
    }

    private static long requiredLong(Map<String, Object> map, String key, String where) {
        return required(map, key, Long.class, where);
    }

    private static OptionalInt optionalInt(Map<String, Object> map, String key, String where) {
        Optional<Long> value = optional(map, key, Long.class, where);
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        long v = value.get();
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(where + ": field \"" + key + "\" is out of range");
        }
        return OptionalInt.of((int) v);
    }
}
