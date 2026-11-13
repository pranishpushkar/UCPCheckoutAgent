package dev.ucplab.faultproxy;

import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One installed {@link RuleSet} with its counters (§12 Matching [CONTRACT]). Rules are tried in order; every rule that
 * matches before the winner still counts the match. A new rule set gets a new {@code Matcher}, which is what resets
 * every counter. Lock-free: atomics only, so concurrent requests never block one another.
 *
 * <p>Determinism: the probability draw for the k-th match of rule i depends only on (seed, i, k), never on the clock
 * or on thread timing, so the same seed gives the same decision for each match index.
 */
final class Matcher {

    sealed interface Decision {
        /** No rule fired: forward the request unchanged. */
        record Pass() implements Decision {
        }

        /** {@code rule} (at {@code ruleIndex}) fired on its {@code matchIndex}-th match. */
        record Fire(Rule rule, int ruleIndex, long matchIndex) implements Decision {
        }
    }

    private static final Decision PASS = new Decision.Pass();

    private final RuleSet ruleSet;
    private final AtomicLong[] matches;
    private final AtomicInteger[] fired;

    Matcher(RuleSet ruleSet) {
        this.ruleSet = Objects.requireNonNull(ruleSet, "ruleSet");
        int n = ruleSet.rules().size();
        matches = new AtomicLong[n];
        fired = new AtomicInteger[n];
        for (int i = 0; i < n; i++) {
            matches[i] = new AtomicLong();
            fired[i] = new AtomicInteger();
        }
    }

    RuleSet ruleSet() {
        return ruleSet;
    }

    Decision decide(String method, String rawPath) {
        for (int i = 0; i < matches.length; i++) {
            Rule rule = ruleSet.rules().get(i);
            Match match = rule.match();
            if (!match.fits(method, rawPath)) {
                continue;
            }
            long k = matches[i].incrementAndGet();
            if (match.nth().isPresent() && match.nth().getAsInt() != k) {
                continue;
            }
            if (match.probability().isPresent() && draw(ruleSet.seed(), i, k) >= match.probability().getAsDouble()) {
                continue;
            }
            if (!claim(i, rule)) {
                continue;
            }
            return new Decision.Fire(rule, i, k);
        }
        return PASS;
    }

    /** Takes one of the rule's {@code times} firings, or reports that none is left. */
    private boolean claim(int i, Rule rule) {
        if (rule.times().isEmpty()) {
            fired[i].incrementAndGet();
            return true;
        }
        int limit = rule.times().getAsInt();
        while (true) {
            int f = fired[i].get();
            if (f >= limit) {
                return false;
            }
            if (fired[i].compareAndSet(f, f + 1)) {
                return true;
            }
        }
    }

    /** A uniform draw in [0, 1) that depends only on its arguments. */
    static double draw(long seed, int ruleIndex, long k) {
        long h = mix64(seed ^ 0x9E3779B97F4A7C15L);
        h = mix64(h + ruleIndex);
        h = mix64(h + k);
        return new SplittableRandom(h).nextDouble();
    }

    /** Stafford's variant 13 of the MurmurHash3 finalizer. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
