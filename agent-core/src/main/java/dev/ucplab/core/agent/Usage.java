package dev.ucplab.core.agent;

/** Tokens one model turn used (6.3, 6.5, D2). Scripted turns use none. */
public record Usage(long inputTokens, long outputTokens) {

    public static final Usage NONE = new Usage(0, 0);

    public Usage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public long total() {
        return inputTokens + outputTokens;
    }

    public Usage plus(Usage other) {
        return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
