package dev.ucplab.runtime;

import dev.ucplab.core.agent.ToolSpec;
import java.util.Map;

/**
 * One of the five tools the model may call (6.2 [CONTRACT], I6). A tool validates its arguments, which are model output
 * and so untrusted, calls one port, and returns canonical JSON for the model. It takes the user only from the run's
 * {@link ToolContext}, never from an argument (T1), and its result never carries an approval token or id, a snapshot
 * hash, a session id or a payment credential (I6, I8, 9.2). Merchant and user text in a result is wrapped by
 * {@link UntrustedText}.
 */
public interface Tool {

    ToolSpec spec();

    ToolOutcome run(ToolContext context, Map<String, Object> arguments);
}
