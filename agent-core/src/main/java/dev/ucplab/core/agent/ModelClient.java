package dev.ucplab.core.agent;

import java.util.List;

/**
 * The model port (6.5 [CONTRACT]): one model turn for a conversation and the tools on offer. The implementation never
 * runs a tool; the agent loop runs every tool call the turn asks for, so budgets (6.3) and I6 are enforced in one place.
 * {@code GeminiModelClient} (agent-adk) is the live implementation and {@code ScriptedModel} (agent-runtime) the
 * scripted one.
 */
public interface ModelClient {
    ModelTurn generate(Conversation conversation, List<ToolSpec> tools);
}
