package dev.ucplab.core.agent;

/**
 * A model call's failure that carries a fixed code (11): what the trace's {@code llm.call} line records for a call that
 * threw, so a server error, a timeout and a dropped connection can be told apart. Implemented by the exceptions a
 * {@link ModelClient} throws. The code is a short identifier such as {@code TIMEOUT}, never a message, a URL or text
 * from the model.
 */
public interface ModelFailure {

    /** The failure's fixed code. */
    String code();
}
