package dev.ucplab.eval.report;

/** How a report row's numbers were produced (13.1, 13.4, m5-plan section 9): every row carries one. */
public enum Mode {
    /** The harness calls Orchestrator.propose itself: no model (the M4 rows). */
    ORCHESTRATOR_DIRECT("orchestrator-direct"),
    /** The agent runtime proposes, driven by a ScriptedModel. */
    AGENT_SCRIPTED("agent-scripted"),
    /** The agent runtime proposes, driven by a live model. */
    AGENT_LIVE("agent-live");

    private final String label;

    Mode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
