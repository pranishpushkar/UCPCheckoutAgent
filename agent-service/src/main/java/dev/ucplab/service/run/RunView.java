package dev.ucplab.service.run;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A run as the API shows it (23.1): the status (one of the seven of 23.1), the reason for {@code failed} and
 * {@code budget_exceeded}, the in-memory messages (empty with {@code messagesRetained} false once the conversation is
 * gone), and the stored record. The record is empty only in the moment before the run thread has written it.
 */
public record RunView(RunId id, String status, Optional<String> reason, List<RunMessage> messages,
                      boolean messagesRetained, Instant startedAt, Optional<RunRecord> record) {

    public static final String RUNNING = "running";
    public static final String WAITING_FOR_USER = "waiting_for_user";
    public static final String WAITING_FOR_APPROVAL = "waiting_for_approval";
    public static final String DONE = "done";
    public static final String BUDGET_EXCEEDED = "budget_exceeded";
    public static final String USER_TIMEOUT = "user_timeout";
    public static final String FAILED = "failed";

    public RunView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(record, "record");
    }
}
