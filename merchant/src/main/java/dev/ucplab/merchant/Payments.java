package dev.ucplab.merchant;

/**
 * Mock payment handler. No real money, no real cards.
 *   tok_ok      approved
 *   tok_decline declined
 *   anything else is an invalid token
 * Latency is adjustable so the lab can hold a checkout in complete_in_progress.
 */
final class Payments {
    record Result(boolean approved, String code) {}

    private volatile long latencyMs;

    Payments(long latencyMs) { this.latencyMs = latencyMs; }

    void setLatencyMs(long ms) { this.latencyMs = Math.max(0, ms); }

    long latencyMs() { return latencyMs; }

    Result charge(String token, long amountMinor) {
        long ms = latencyMs;
        if (ms > 0) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if ("tok_ok".equals(token)) return new Result(true, "approved");
        if ("tok_decline".equals(token)) return new Result(false, "card_declined");
        return new Result(false, "invalid_token");
    }
}
