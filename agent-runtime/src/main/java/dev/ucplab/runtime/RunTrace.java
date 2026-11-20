package dev.ucplab.runtime;

import dev.ucplab.core.agent.ModelFailure;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.json.CanonicalJson;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * A run's spans as JSON lines, written to {@code trace.jsonl} by the harness (11, D7), with the section 11 span names:
 * {@code llm.call}, {@code tool.call} and {@code agent.run}. A line holds names, counts, durations and codes only: never
 * the prompt, model text, tool arguments or tool results, and never a tool name the model made up.
 */
public final class RunTrace {

    /** The outcome of a failed model call whose failure carries no code. */
    public static final String UNKNOWN_FAILURE = "unknown";

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

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

    /**
     * A model call that threw: its index, how long it took, the failure's fixed code as the outcome, or
     * {@link #UNKNOWN_FAILURE} when it carries none ({@link ModelFailure}) or the code is not an identifier, and its
     * {@link #causeOf cause} (6.5). Never the exception's message.
     */
    void llmCallFailed(int index, Duration took, RuntimeException failure) {
        Map<String, Object> span = span("llm.call");
        span.put("index", index);
        span.put("duration_ms", took.toMillis());
        span.put("outcome", failure instanceof ModelFailure f && f.code() != null && CODE.matcher(f.code()).matches()
                ? f.code() : UNKNOWN_FAILURE);
        span.put("cause", causeOf(failure));
        lines.add(CanonicalJson.write(span));
    }

    /**
     * 6.5: a model call's failure as a cause, its {@link ModelFailure#cause}, or {@code exception_<class>} for any other
     * exception; {@link #UNKNOWN_FAILURE} when that is not 1 to 64 of {@code [A-Za-z0-9_]}, so no text gets through.
     */
    static String causeOf(RuntimeException failure) {
        String cause = failure instanceof ModelFailure f ? f.cause()
                : "exception_" + failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_");
        return cause != null && RunRecord.CAUSE.matcher(cause).matches() ? cause : UNKNOWN_FAILURE;
    }

    void toolCall(int index, String tool, Duration took, String code) {
        Map<String, Object> span = span("tool.call");
        span.put("index", index);
        span.put("name", tool);
        span.put("duration_ms", took.toMillis());
        span.put("code", code);
        lines.add(CanonicalJson.write(span));
    }

    /**
     * A tool that threw (the run ends {@code tool_error}): its index, name and duration, the code {@code error}, the
     * exception's simple class name, and the first frame of our own code it passed through ({@code Class.method}), so a
     * {@code tool_error} can be explained. Never the exception's message, which may carry merchant or user text (I8).
     */
    void toolCallFailed(int index, String tool, Duration took, RuntimeException failure) {
        Map<String, Object> span = span("tool.call");
        span.put("index", index);
        span.put("name", tool);
        span.put("duration_ms", took.toMillis());
        span.put("code", "error");
        span.put("exception", failure.getClass().getSimpleName());
        span.put("at", ownFrame(failure));
        lines.add(CanonicalJson.write(span));
    }

    /** The first stack frame in {@code dev.ucplab}, as the class's simple name and the method, or "unknown". */
    private static String ownFrame(Throwable failure) {
        for (StackTraceElement frame : failure.getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith("dev.ucplab.")) {
                return cls.substring(cls.lastIndexOf('.') + 1) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return UNKNOWN_FAILURE;
    }

    /** The run's end; {@code cause} is a {@code model_error}'s (6.5), already checked. */
    void run(String outcome, String detail, Optional<String> cause, int modelCalls, int toolCalls, long inputTokens,
             long outputTokens, Duration took) {
        Map<String, Object> span = span("agent.run");
        span.put("outcome", outcome);
        span.put("detail", detail);
        cause.ifPresent(c -> span.put("cause", c));
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
