package dev.ucplab.core.intent;

/** States of a purchase intent. Contract 7.1. */
public enum IntentState {
    PROPOSED, QUOTING, AWAITING_APPROVAL, APPROVED, COMPLETING, RECONCILING,
    COMPLETED, REJECTED, FAILED_NOT_CHARGED, ESCALATED;

    /** COMPLETED, REJECTED, FAILED_NOT_CHARGED and ESCALATED are terminal (7.1). */
    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, REJECTED, FAILED_NOT_CHARGED, ESCALATED -> true;
            default -> false;
        };
    }
}
