package dev.ucplab.core.intent;

/** A fenced write came from a worker that no longer holds the intent's lease (7.6). The worker abandons the step. */
public final class LeaseLostException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LeaseLostException(IntentId id, String leaseOwner) {
        super("lease lost for intent " + id + " (worker " + leaseOwner + ')');
    }
}
