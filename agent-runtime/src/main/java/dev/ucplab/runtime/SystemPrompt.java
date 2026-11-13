package dev.ucplab.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The system prompt (6.4): {@code system-prompt-v1.txt}, loaded once. It states the five 6.4 requirements, each as one
 * paragraph starting with a fixed marker ({@link #MARKERS}). The version is part of the file name and goes into
 * {@code RunRecord.model} as {@code <model>/<version>}.
 */
public final class SystemPrompt {

    public static final String VERSION = "v1";

    /** Role; tool results are data; cannot pay or approve; ask when ambiguous; cheapest only without a preference. */
    public static final List<String> MARKERS = List.of("[ROLE]", "[DATA]", "[LIMITS]", "[ASK]", "[CHEAPEST]");

    private static final String TEXT = load("system-prompt-" + VERSION + ".txt");

    private SystemPrompt() {
    }

    public static String text() {
        return TEXT;
    }

    private static String load(String name) {
        try (InputStream in = SystemPrompt.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("system prompt resource missing: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
