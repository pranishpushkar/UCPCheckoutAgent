package dev.ucplab.eval.report;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * A live evaluation's own facts for the report (C16, m5-plan section 10): the model and prompt version, the caps, what
 * was spent, and why it stopped early, if it did. The price file is the report's {@code prices}.
 *
 * @param stopped the cap that stopped new runs; a report with one is REDUCED and exits non-zero
 */
public record LiveRun(String model, String promptVersion, BigDecimal maxCostUsd, long maxTokensTotal, long tokens,
                      BigDecimal estimatedCostUsd, Optional<String> stopped) {

    public LiveRun {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(maxCostUsd, "maxCostUsd");
        Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd");
        Objects.requireNonNull(stopped, "stopped");
    }
}
