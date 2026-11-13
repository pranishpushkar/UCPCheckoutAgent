package dev.ucplab.service.run;

import java.util.Objects;

/** A request the supervisor refuses, with our own kind; the controller maps it to an error code (C14). */
public final class RunRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why: not the caller's run (404), no question or continuation open (409), or a limit (429). */
    public enum Kind { NOT_FOUND, NOT_WAITING, RATE_LIMITED }

    private final Kind kind;

    RunRefused(Kind kind) {
        super(Objects.requireNonNull(kind, "kind").name(), null, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
