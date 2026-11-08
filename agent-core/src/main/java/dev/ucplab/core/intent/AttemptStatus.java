package dev.ucplab.core.intent;

/**
 * Status of a merchant attempt (section 10). SENT is written before the call (7.3 step 3); RESPONDED or
 * UNCERTAIN after it. A SENT attempt found by recovery is treated as uncertain (7.3).
 */
public enum AttemptStatus {
    SENT, RESPONDED, UNCERTAIN
}
