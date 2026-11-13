package dev.ucplab.runtime;

import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.json.LenientJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A {@link ScriptedModel} script (m5-plan section 7), read with core's {@link LenientJson}:
 *
 * <pre>
 * {"name": "...", "expectToolSequence": ["search_catalog", ...],
 *  "turns": [{"when": {"tool": "...", "resultMatches": "regex"}, "call": [{"tool": "...", "args": {...}}],
 *             "text": "...", "usage": {"inputTokens": 0, "outputTokens": 0}}, ...],
 *  "rules": [{"when": {...}, "call": [...], "text": "..."}],
 *  "usage": {"inputTokens": 0, "outputTokens": 0}}
 * </pre>
 *
 * A turn or rule has a {@code call} list, a {@code text}, or both; {@code when} is optional on a turn and required on a
 * rule. {@code resultMatches} is a regular expression found in the tool result's canonical JSON. Strings in
 * {@code args} and {@code text} may hold {@code ${result.<path>}} templates. Usage is 0/0 unless the script or the turn
 * says otherwise. Unknown fields are refused, so a misspelt key cannot be ignored silently.
 */
record Script(String name, Optional<List<String>> expectToolSequence, List<Step> turns, List<Step> rules,
              Usage usage) {

    Script {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expectToolSequence, "expectToolSequence");
        expectToolSequence = expectToolSequence.map(List::copyOf);
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        Objects.requireNonNull(usage, "usage");
    }

    /** What a turn or rule does when it answers. {@code usage} overrides the script's. */
    record Step(Optional<When> when, List<Call> calls, Optional<String> text, Optional<Usage> usage) {
        Step {
            Objects.requireNonNull(when, "when");
            calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(usage, "usage");
        }
    }

    /** Matches a tool result by the tool's name and, when given, a regular expression over its JSON. */
    record When(String tool, Optional<Pattern> resultMatches) {
        When {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(resultMatches, "resultMatches");
        }

        boolean matches(String resultTool, String json) {
            return tool.equals(resultTool) && resultMatches.map(p -> p.matcher(json).find()).orElse(true);
        }
    }

    /** One tool call a step makes. {@code args} may hold templates. */
    record Call(String tool, Map<String, Object> args) {
        Call {
            Objects.requireNonNull(tool, "tool");
            args = Map.copyOf(Objects.requireNonNull(args, "args"));
        }
    }

    /**
     * Reads a script.
     *
     * @throws IllegalArgumentException if it is not a valid script; the message names the field
     */
    static Script parse(String json) {
        Objects.requireNonNull(json, "json");
        Map<String, Object> root = LenientJson.readObject(json);
        only(root, Set.of("name", "expectToolSequence", "turns", "rules", "usage"), "script");
        String name = string(root.get("name"), "name");
        Optional<List<String>> expect = root.containsKey("expectToolSequence")
                ? Optional.of(strings(root.get("expectToolSequence"), "expectToolSequence")) : Optional.empty();
        List<Step> turns = steps(root.get("turns"), "turns", false);
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("turns must not be empty");
        }
        List<Step> rules = root.containsKey("rules") ? steps(root.get("rules"), "rules", true) : List.of();
        Usage usage = root.containsKey("usage") ? usage(root.get("usage"), "usage") : Usage.NONE;
        return new Script(name, expect, turns, rules, usage);
    }

    private static List<Step> steps(Object value, String field, boolean whenRequired) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be a list");
        }
        List<Step> out = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> step = object(o, field + " entry");
            only(step, Set.of("when", "call", "text", "usage"), field + " entry");
            Optional<When> when = step.containsKey("when") ? Optional.of(when(step.get("when"))) : Optional.empty();
            if (whenRequired && when.isEmpty()) {
                throw new IllegalArgumentException(field + " entry needs when");
            }
            List<Call> calls = new ArrayList<>();
            if (step.containsKey("call")) {
                if (!(step.get("call") instanceof List<?> callList) || callList.isEmpty()) {
                    throw new IllegalArgumentException("call must be a non-empty list");
                }
                for (Object c : callList) {
                    calls.add(call(c));
                }
            }
            Optional<String> text = step.containsKey("text")
                    ? Optional.of(textValue(step.get("text"))) : Optional.empty();
            if (calls.isEmpty() && text.isEmpty()) {
                throw new IllegalArgumentException(field + " entry needs call or text");
            }
            Optional<Usage> usage = step.containsKey("usage")
                    ? Optional.of(usage(step.get("usage"), "usage")) : Optional.empty();
            out.add(new Step(when, calls, text, usage));
        }
        return out;
    }

    private static When when(Object value) {
        Map<String, Object> when = object(value, "when");
        only(when, Set.of("tool", "resultMatches"), "when");
        String tool = string(when.get("tool"), "when.tool");
        Optional<Pattern> pattern = Optional.empty();
        if (when.containsKey("resultMatches")) {
            try {
                pattern = Optional.of(Pattern.compile(string(when.get("resultMatches"), "when.resultMatches")));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("when.resultMatches is not a regular expression");
            }
        }
        return new When(tool, pattern);
    }

    private static Call call(Object value) {
        Map<String, Object> call = object(value, "call entry");
        only(call, Set.of("tool", "args"), "call entry");
        String tool = string(call.get("tool"), "call.tool");
        Map<String, Object> args = call.containsKey("args") ? object(call.get("args"), "call.args") : Map.of();
        if (args.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("call.args must not hold null");
        }
        return new Call(tool, args);
    }

    private static Usage usage(Object value, String field) {
        Map<String, Object> usage = object(value, field);
        only(usage, Set.of("inputTokens", "outputTokens"), field);
        return new Usage(count(usage.getOrDefault("inputTokens", 0L), field + ".inputTokens"),
                count(usage.getOrDefault("outputTokens", 0L), field + ".outputTokens"));
    }

    private static long count(Object value, String field) {
        if (value instanceof Long n && n >= 0) {
            return n;
        }
        throw new IllegalArgumentException(field + " must be a whole number, at least 0");
    }

    private static List<String> strings(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be a list of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            out.add(string(o, field + " entry"));
        }
        return out;
    }

    private static String string(Object value, String field) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException(field + " must be a non-blank string");
    }

    private static String textValue(Object value) {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("text must be a string");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(field + " must be an object");
    }

    private static void only(Map<String, Object> map, Set<String> allowed, String where) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown field in " + where + ": " + key);
            }
        }
    }
}
