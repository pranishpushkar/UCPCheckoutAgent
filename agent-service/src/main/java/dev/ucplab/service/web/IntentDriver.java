package dev.ucplab.service.web;

import dev.ucplab.core.intent.IntentId;

/**
 * Drives an approved intent at once, as {@code RecoveryWorker.driveNow} does (7.6, C8). An interface so the approval
 * route does not depend on the worker class itself.
 */
@FunctionalInterface
public interface IntentDriver {

    /** True when a driver was started; false when another driver or worker already has the intent. */
    boolean driveNow(IntentId id);
}
