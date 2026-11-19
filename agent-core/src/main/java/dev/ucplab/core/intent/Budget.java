package dev.ucplab.core.intent;

import java.util.Objects;

/**
 * The user's budget for a run (9.1 P9, Appendix B): {@code amountMinor} in {@code currency}'s minor units. It comes from
 * the user's own Budget field on {@code POST /api/runs} (23.1), is copied onto each intent the run proposes, and is never
 * set, raised or cleared by model output (6.2).
 */
public record Budget(long amountMinor, String currency) {

    public Budget {
        Objects.requireNonNull(currency, "currency");
    }
}
