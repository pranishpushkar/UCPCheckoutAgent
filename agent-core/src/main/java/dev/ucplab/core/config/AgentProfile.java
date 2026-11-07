package dev.ucplab.core.config;

import java.util.Locale;
import java.util.Objects;

/** The deployment profile, from {@code AGENT_PROFILE} (section 15). */
public enum AgentProfile {
    LOCAL, DEMO, PROD;

    /** Parses {@code local}, {@code demo} or {@code prod}, ignoring case. */
    public static AgentProfile parse(String value) {
        Objects.requireNonNull(value, "value");
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
