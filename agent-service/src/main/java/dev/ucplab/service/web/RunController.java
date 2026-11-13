package dev.ucplab.service.web;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.ToolEvent;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.service.run.RunMessage;
import dev.ucplab.service.run.RunRefused;
import dev.ucplab.service.run.RunSupervisor;
import dev.ucplab.service.run.RunView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Talking to the agent (23.1 [CONTRACT], m6-plan section 3.1). The caller comes from the session (T1); another user's
 * run is 404 exactly like a missing one (T6). A message is 1 to 2,000 characters after control characters other than
 * newline and tab are removed; anything else is 422 with a fixed message that never echoes it.
 *
 * <p>{@code GET} returns the stored run plus the live messages while this instance holds them. Of everything in it, only
 * {@code messages[]} can hold text from the user, the questions and the agent's reply; every other field is a number, a
 * time, an id or one of our own fixed codes, so merchant and model text can never come out of it (m6-plan section 3.5).
 */
@RestController
final class RunController {

    static final String BAD_MESSAGE = "message must be 1 to 2000 characters";
    static final int MAX_MESSAGE = 2000;

    private final RunSupervisor supervisor;

    RunController(RunSupervisor supervisor) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    @PostMapping("/api/runs")
    ResponseEntity<Map<String, Object>> start(AuthContext auth, @RequestBody Map<String, Object> body) {
        String message = message(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(summary(refusing(() -> supervisor.start(auth, message))));
    }

    @PostMapping("/api/runs/{id}/messages")
    Map<String, Object> answer(AuthContext auth, @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        RunId run = runId(id);
        String message = message(body);
        return summary(refusing(() -> supervisor.answer(auth, run, message)));
    }

    @GetMapping("/api/runs/{id}")
    Map<String, Object> run(AuthContext auth, @PathVariable("id") String id) {
        RunView view = supervisor.get(auth, runId(id)).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND));
        Map<String, Object> out = summary(view);
        view.record().ifPresent(r -> detail(out, r));
        if (view.record().isEmpty()) {
            out.put("model", "");
        }
        out.put("started_at", view.startedAt().toString());
        out.put("messages_retained", view.messagesRetained());
        return out;
    }

    /** The 201 and 200 shape of 23.1: {@code run_id, status, reason?, messages[], intent_ids[]}. */
    private static Map<String, Object> summary(RunView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", view.id().value());
        out.put("status", view.status());
        view.reason().ifPresent(r -> out.put("reason", r));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (RunMessage m : view.messages()) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", m.role());
            message.put("kind", m.kind());
            message.put("text", m.text());
            message.put("at", m.at().toString());
            messages.add(message);
        }
        out.put("messages", messages);
        out.put("intent_ids", view.record().map(RunRecord::intentIds).orElse(List.of()).stream()
                .map(IntentId::value).toList());
        return out;
    }

    private static void detail(Map<String, Object> out, RunRecord r) {
        out.put("model", r.model());
        r.endedAt().ifPresent(e -> out.put("ended_at", e.toString()));
        List<Map<String, Object>> events = new ArrayList<>();
        for (ToolEvent e : r.toolEvents()) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("tool", e.tool());
            event.put("summary", e.summary());
            event.put("took_ms", e.took().toMillis());
            events.add(event);
        }
        out.put("tool_events", events);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("model_calls", r.modelCalls());
        counts.put("tool_calls", r.toolCalls());
        counts.put("input_tokens", r.inputTokens());
        counts.put("output_tokens", r.outputTokens());
        out.put("counts", counts);
    }

    private static RunView refusing(java.util.function.Supplier<RunView> call) {
        try {
            return call.get();
        } catch (RunRefused e) {
            throw switch (e.kind()) {
                case NOT_FOUND -> ApiException.of(ErrorCode.NOT_FOUND);
                case NOT_WAITING -> ApiException.of(ErrorCode.RUN_NOT_WAITING);
                case RATE_LIMITED -> ApiException.of(ErrorCode.RATE_LIMITED);
            };
        }
    }

    /** A blank or malformed id is 404, like a missing one (T6). */
    private static RunId runId(String raw) {
        try {
            return new RunId(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.of(ErrorCode.NOT_FOUND);
        }
    }

    private static String message(Map<String, Object> body) {
        if (!(body.get("message") instanceof String raw)) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, BAD_MESSAGE);
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        raw.codePoints().filter(c -> !Character.isISOControl(c) || c == '\n' || c == '\t')
                .forEach(cleaned::appendCodePoint);
        String message = cleaned.toString();
        if (message.isBlank() || message.codePointCount(0, message.length()) > MAX_MESSAGE) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, BAD_MESSAGE);
        }
        return message;
    }
}
