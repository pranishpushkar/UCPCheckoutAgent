package dev.ucplab.runtime;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays a {@link Script} as a model (6.5, m5-plan section 7). No randomness and no clock: the same script and the
 * same tool results give the same turns.
 *
 * <p>On each {@link #generate}, the rules are tried in order against the tool results since the model's last turn; the
 * first match answers and uses no turn. Otherwise the next turn answers, if its {@code when} (when it has one) matches
 * one of those results. A {@code ${result.<path>}} template (fields and {@code [n]}) takes a value from the result its
 * {@code when} matched, or else from the latest tool result; a string that is exactly one template takes the value as
 * it is (a number stays a number). Tool call ids are {@code call-1}, {@code call-2} and so on.
 *
 * <p>{@link ScriptDriftException} is thrown when no rule matches and the next turn's {@code when} does not, when the
 * turns have run out, when a template path is missing, and from {@link #checkToolSequence} when the tool calls this
 * model made differ from {@code expectToolSequence}. One instance per run; not for use by two threads.
 */
public final class ScriptedModel implements ModelClient {

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{result\\.([^}]+)}");
    private static final Pattern SEGMENT = Pattern.compile("([A-Za-z0-9_]+)((?:\\[\\d{1,9}])*)");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d{1,9})]");

    private final Script script;
    private final List<String> calledTools = new ArrayList<>();
    private int nextTurn;
    private int nextCallId = 1;

    private ScriptedModel(Script script) {
        this.script = script;
    }

    /** @throws IllegalArgumentException if {@code json} is not a valid script */
    public static ScriptedModel parse(String json) {
        return new ScriptedModel(Script.parse(json));
    }

    /** Reads a script from the class path, relative to {@code anchor}'s package or absolute with a leading '/'. */
    public static ScriptedModel fromResource(Class<?> anchor, String name) {
        try (InputStream in = anchor.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("script resource missing: " + name);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String name() {
        return script.name();
    }

    @Override
    public ModelTurn generate(Conversation conversation, List<ToolSpec> tools) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(tools, "tools");
        List<Message.ToolResult> recent = sinceLastModelTurn(conversation.messages());
        Optional<Message.ToolResult> latest = latestResult(conversation.messages());
        for (Script.Step rule : script.rules()) {
            Optional<Message.ToolResult> matched = match(rule.when().orElseThrow(), recent);
            if (matched.isPresent()) {
                return answer(rule, matched);
            }
        }
        if (nextTurn >= script.turns().size()) {
            throw new ScriptDriftException(script.name(), "the turns ran out at turn " + (nextTurn + 1));
        }
        Script.Step turn = script.turns().get(nextTurn);
        Optional<Message.ToolResult> source = latest;
        if (turn.when().isPresent()) {
            source = match(turn.when().get(), recent);
            if (source.isEmpty()) {
                throw new ScriptDriftException(script.name(), "turn " + (nextTurn + 1) + "'s when did not match "
                        + "and no rule did");
            }
        }
        nextTurn++;
        return answer(turn, source);
    }

    /**
     * Checks the tool calls this model made, in order, against {@code expectToolSequence}; a script without one passes.
     *
     * @throws ScriptDriftException if they differ
     */
    public void checkToolSequence() {
        if (script.expectToolSequence().isPresent() && !script.expectToolSequence().get().equals(calledTools)) {
            throw new ScriptDriftException(script.name(), "the tool sequence " + calledTools + " is not the expected "
                    + script.expectToolSequence().get());
        }
    }

    /** The tools this model has called so far, in order. */
    public List<String> calledTools() {
        return List.copyOf(calledTools);
    }

    private ModelTurn answer(Script.Step step, Optional<Message.ToolResult> source) {
        Optional<Object> result = source.map(r -> LenientJson.readObject(r.json()));
        List<ToolCall> calls = new ArrayList<>();
        for (Script.Call call : step.calls()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) fill(call.args(), result);
            calls.add(new ToolCall("call-" + nextCallId++, call.tool(), args));
            calledTools.add(call.tool());
        }
        String text = step.text().map(t -> String.valueOf(fill(t, result))).orElse("");
        return new ModelTurn(text, calls, step.usage().orElse(script.usage()));
    }

    private static Optional<Message.ToolResult> match(Script.When when, List<Message.ToolResult> recent) {
        return recent.stream().filter(r -> when.matches(r.tool(), r.json())).reduce((a, b) -> b);
    }

    private static List<Message.ToolResult> sinceLastModelTurn(List<Message> messages) {
        List<Message.ToolResult> out = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && !(messages.get(i) instanceof Message.Model); i--) {
            if (messages.get(i) instanceof Message.ToolResult r) {
                out.addFirst(r);
            }
        }
        return out;
    }

    private static Optional<Message.ToolResult> latestResult(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.ToolResult r) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    private Object fill(Object value, Optional<Object> result) {
        return switch (value) {
            case String s -> fillString(s, result);
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put((String) k, fill(v, result)));
                yield out;
            }
            case List<?> list -> list.stream().map(v -> fill(v, result)).toList();
            default -> value;
        };
    }

    private Object fillString(String s, Optional<Object> result) {
        Matcher whole = TEMPLATE.matcher(s);
        if (whole.matches()) {
            return resolve(whole.group(1), result);
        }
        Matcher m = TEMPLATE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(resolve(m.group(1), result))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private Object resolve(String path, Optional<Object> result) {
        if (result.isEmpty()) {
            throw new ScriptDriftException(script.name(), "template result." + path + " has no tool result to read");
        }
        Object current = result.get();
        for (String part : path.split("\\.", -1)) {
            Matcher seg = SEGMENT.matcher(part);
            if (!seg.matches() || !(current instanceof Map<?, ?> map) || !map.containsKey(seg.group(1))) {
                throw missing(path);
            }
            current = map.get(seg.group(1));
            Matcher index = INDEX.matcher(seg.group(2));
            while (index.find()) {
                int i = Integer.parseInt(index.group(1));
                if (!(current instanceof List<?> list) || i >= list.size()) {
                    throw missing(path);
                }
                current = list.get(i);
            }
        }
        if (current == null || current instanceof Map || current instanceof List) {
            throw missing(path);
        }
        return current;
    }

    private ScriptDriftException missing(String path) {
        return new ScriptDriftException(script.name(), "template path result." + path + " is missing");
    }
}
