package dev.ucplab.runtime;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.json.CanonicalJson;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A run's spans as JSON lines, written to {@code trace.jsonl} by the harness (11, D7), with the section 11 span names:
 * {@code llm.call}, {@code tool.call} and {@code agent.run}. A line holds names, counts, durations and codes only: never
 * the prompt, model text, tool arguments or tool results, and never a tool name the model made up.
 */
public final class RunTrace {

    private final RunId runId;
    private final List<String> lines = new CopyOnWriteArrayList<>();

    public RunTrace(RunId runId) {
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    void llmCall(int index, Duration took, long inputTokens, long outputTokens, int toolCalls) {
        Map<String, Object> span = span("llm.call");
        span.put("index", index);
        span.put("duration_ms", took.toMillis());
        span.put("input_tokens", inputTokens);
        span.put("output_tokens", outputTokens);
        span.put("tool_calls", toolCalls);
        lines.add(CanonicalJson.write(span));
    }

    void toolCall(int index, String tool, Duration took, String code) {
        Map<String, Object> span = span("tool.call");
        span.put("index", index);
        span.put("name", tool);
        span.put("duration_ms", took.toMillis());
        span.put("code", code);
        lines.add(CanonicalJson.write(span));
    }

    void run(String outcome, String detail, int modelCalls, int toolCalls, long inputTokens, long outputTokens,
             Duration took) {
        Map<String, Object> span = span("agent.run");
        span.put("outcome", outcome);
        span.put("detail", detail);
        span.put("model_calls", modelCalls);
        span.put("tool_calls", toolCalls);
        span.put("input_tokens", inputTokens);
        span.put("output_tokens", outputTokens);
        span.put("duration_ms", took.toMillis());
        lines.add(CanonicalJson.write(span));
    }

    /** The lines so far, in order. */
    public List<String> lines() {
        return List.copyOf(lines);
    }

    /** The lines as {@code trace.jsonl} content: one line each, each ending with a newline. */
    public String jsonl() {
        StringBuilder out = new StringBuilder();
        lines.forEach(l -> out.append(l).append('\n'));
        return out.toString();
    }

    private Map<String, Object> span(String name) {
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("span", name);
        span.put("run_id", runId.value());
        return span;
    }
}
