package dev.ucplab.runtime;

/**
 * A scripted run left its script (m5-plan section 7): no turn or rule matched, the turns ran out, a template path was
 * missing, or the tool sequence differed from {@code expectToolSequence}. The harness counts it as an aborted run, never
 * as an expected outcome, so a drifted script can never pass. The message names the script and the reason, never a
 * tool result.
 */
public final class ScriptDriftException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ScriptDriftException(String script, String reason) {
        super("script " + script + " drifted: " + reason);
    }
}
