package dev.ucplab.service.run;

import java.util.Set;

/** The reasons only the service writes (C13 replaces this with the supervisor's own constant). */
final class RunSupervisorCodes {
    static final Set<String> SERVICE_REASONS = Set.of("interrupted");

    private RunSupervisorCodes() {
    }
}
