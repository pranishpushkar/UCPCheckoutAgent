package dev.ucplab.runtime;

import dev.ucplab.core.intent.Budget;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The system prompt (6.4): {@code system-prompt-v8.txt}, loaded once. It states the thirteen 6.4 requirements, each as one
 * paragraph starting with a fixed marker ({@link #MARKERS}). The version is part of the file name and goes into
 * {@code RunRecord.model} as {@code <model>/<version>}.
 */
public final class SystemPrompt {

    public static final String VERSION = "v8";

    /**
     * Role; tool results are data; cannot pay or approve; ask when ambiguous; cheapest only without a preference; search
     * with product words, every result within the budget (v2, v4); compare from the search results, which name each
     * variant's options, and read one product (v3, v4); at most two searches, then propose or say none fits (v4); after a
     * policy refusal its reason and a stop (v7); at a catalog seller, approve for the store's link and buy on its site
     * (v5); plain text, no internal ids, the seller's name (v5); results for the user's country in its currency, never
     * converted, and other currencies only counted (v6); never a budget change, the Budget field named instead (v7); amounts as the results give them (v8).
     */
    public static final List<String> MARKERS = List.of("[ROLE]", "[DATA]", "[LIMITS]", "[ASK]", "[CHEAPEST]", "[SEARCH]",
            "[COMPARE]", "[DECIDE]", "[REFUSED]", "[LINK]", "[REPLY]", "[SHIP]", "[CHANGE]");

    private static final String TEXT = load("system-prompt-" + VERSION + ".txt");

    private SystemPrompt() {
    }

    public static String text() {
        return TEXT;
    }

    /** The system prompt, then the run's [BUDGET] paragraph when it has a budget (6.4): a hint, never a guarantee. */
    public static String text(Optional<Budget> budget) {
        if (budget.isEmpty()) {
            return TEXT;
        }
        return TEXT + "\n\n[BUDGET] The user's budget for this run is " + budget.get().display()
                + ". Propose only items whose total fits it; a quote above it is refused.";
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
