package dev.ucplab.core.agent;

/**
 * A live {@link ModelClient} implementation, found through {@link java.util.ServiceLoader} (m5-plan section 3, Decision
 * 1). agent-adk registers the Gemini one; the harness reaches it through {@code ModelClients.load} in agent-runtime and
 * never names it, so no module but agent-adk sees a Gemini SDK type.
 */
public interface ModelClientProvider {

    /** The name {@code ModelClients.load} looks it up by, such as {@code "gemini"}. */
    String name();

    /** A client for one run or one evaluation; it never runs a tool (6.5). */
    ModelClient create(ModelKey key, ModelClientSettings settings);
}
