package dev.ucplab.faultproxy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.faultproxy.Action.FailBeforeForward;
import dev.ucplab.faultproxy.Action.ForwardThenDropResponse;
import dev.ucplab.faultproxy.Matcher.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MatcherTest {

    private static final String COMPLETE = "/ucp/v1/checkout-sessions/cs_1/complete";
    private static final Match ON_COMPLETE = Match.of("POST", "/ucp/v1/checkout-sessions/*/complete");

    private static Matcher matcher(long seed, Rule... rules) {
        return new Matcher(new RuleSet(seed, List.of(rules)));
    }

    private static boolean fires(Decision d, String ruleId) {
        return d instanceof Decision.Fire f && f.rule().id().equals(ruleId);
    }

    // Covers: §12 Matching (no rules: every request passes)
    @Test
    void decide_withNoRules_passes() {
        assertThat(matcher(1).decide("POST", COMPLETE)).isInstanceOf(Decision.Pass.class);
    }

    // Covers: §12 Matching (nth fires on exactly the k-th match and on no other)
    @Test
    void decide_nth_firesOnlyOnThatMatch() {
        Matcher m = matcher(1, new Rule("third", ON_COMPLETE.withNth(3), new ForwardThenDropResponse()));

        List<Boolean> fired = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fired.add(fires(m.decide("POST", COMPLETE), "third"));
        }

        assertThat(fired).containsExactly(false, false, true, false, false, false);
    }

    // Covers: §12 Matching (a request that does not match leaves the counter alone)
    @Test
    void decide_nonMatchingRequestsDoNotCount() {
        Matcher m = matcher(1, new Rule("second", ON_COMPLETE.withNth(2), new ForwardThenDropResponse()));

        assertThat(fires(m.decide("POST", COMPLETE), "second")).isFalse();
        assertThat(fires(m.decide("GET", "/ucp/v1/checkout-sessions/cs_1"), "second")).isFalse();
        assertThat(fires(m.decide("GET", COMPLETE), "second")).isFalse();
        assertThat(fires(m.decide("POST", COMPLETE), "second")).isTrue();
    }

    // Covers: §12 Matching (times caps the firings; absent means unlimited)
    @Test
    void decide_times_capsTheFirings() {
        Matcher m = matcher(1, new Rule("twice", ON_COMPLETE, FailBeforeForward.status(503)).withTimes(2));

        List<Boolean> fired = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fired.add(fires(m.decide("POST", COMPLETE), "twice"));
        }

        assertThat(fired).containsExactly(true, true, false, false, false);
    }

    // Covers: §12 Matching (rules are tried in order; the first that fires wins; earlier matching rules still count)
    @Test
    void decide_firstFiringRuleWins_andEarlierMatchesStillCount() {
        Matcher m = matcher(1,
                new Rule("first-on-2nd", ON_COMPLETE.withNth(2), FailBeforeForward.status(500)),
                new Rule("every", ON_COMPLETE, FailBeforeForward.status(503)),
                new Rule("never-reached", ON_COMPLETE.withNth(1), FailBeforeForward.status(429)));

        Decision d1 = m.decide("POST", COMPLETE);
        Decision d2 = m.decide("POST", COMPLETE);
        Decision d3 = m.decide("POST", COMPLETE);

        assertThat(d1).isEqualTo(new Decision.Fire(m.ruleSet().rules().get(1), 1, 1));
        assertThat(d2).isEqualTo(new Decision.Fire(m.ruleSet().rules().get(0), 0, 2));
        // The first rule counted the third match too (k = 3), so the second rule fires on its own match k = 2.
        assertThat(d3).isEqualTo(new Decision.Fire(m.ruleSet().rules().get(1), 1, 2));
    }

    // Covers: §12 Matching (a rule that matches but is used up does not stop a later rule from firing)
    @Test
    void decide_usedUpRuleFallsThroughToTheNext() {
        Matcher m = matcher(1,
                new Rule("once", ON_COMPLETE, FailBeforeForward.status(500)).withTimes(1),
                new Rule("then", ON_COMPLETE, FailBeforeForward.status(503)));

        assertThat(fires(m.decide("POST", COMPLETE), "once")).isTrue();
        assertThat(fires(m.decide("POST", COMPLETE), "then")).isTrue();
    }

    // Covers: §12 Matching (replacing the rule set resets every counter: a new Matcher starts at zero)
    @Test
    void newMatcher_startsCountingAgain() {
        Rule first = new Rule("first", ON_COMPLETE.withNth(1), new ForwardThenDropResponse());
        Matcher old = matcher(1, first);
        old.decide("POST", COMPLETE);

        assertThat(fires(old.decide("POST", COMPLETE), "first")).isFalse();
        assertThat(fires(matcher(1, first).decide("POST", COMPLETE), "first")).isTrue();
    }

    // Covers: §12 Determinism (probability 0 never fires, probability 1 always fires)
    @Test
    void decide_probabilityBounds() {
        Matcher never = matcher(9, new Rule("p0", ON_COMPLETE.withProbability(0.0), new ForwardThenDropResponse()));
        Matcher always = matcher(9, new Rule("p1", ON_COMPLETE.withProbability(1.0), new ForwardThenDropResponse()));

        for (int i = 0; i < 1000; i++) {
            assertThat(fires(never.decide("POST", COMPLETE), "p0")).isFalse();
            assertThat(fires(always.decide("POST", COMPLETE), "p1")).isTrue();
        }
    }

    // Covers: §12 Determinism (the same seed gives the same decision for each match index; another seed differs)
    @Test
    void decide_sameSeed_sameDecisionPerIndex() {
        List<Boolean> a = probabilityRun(42);
        List<Boolean> b = probabilityRun(42);
        List<Boolean> c = probabilityRun(43);

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        long hits = a.stream().filter(x -> x).count();
        assertThat(hits).isBetween(200L, 400L);
    }

    private static List<Boolean> probabilityRun(long seed) {
        Matcher m = matcher(seed, new Rule("p", ON_COMPLETE.withProbability(0.3), new ForwardThenDropResponse()));
        List<Boolean> out = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            out.add(fires(m.decide("POST", COMPLETE), "p"));
        }
        return out;
    }

    // Covers: §12 Determinism (the draw depends only on (seed, rule index, k): a pure function)
    @Test
    void draw_isAPureFunctionOfSeedRuleAndIndex() {
        for (long k = 1; k <= 100; k++) {
            assertThat(Matcher.draw(7, 2, k)).isEqualTo(Matcher.draw(7, 2, k)).isBetween(0.0, 1.0);
        }
        assertThat(Matcher.draw(7, 2, 1)).isNotEqualTo(Matcher.draw(7, 3, 1));
        assertThat(Matcher.draw(7, 2, 1)).isNotEqualTo(Matcher.draw(8, 2, 1));
        assertThat(Matcher.draw(7, 2, 1)).isNotEqualTo(Matcher.draw(7, 2, 2));
    }

    // Covers: §12 Matching and Determinism, 7.8 (24 threads x 1000 matches: exactly `times` fires, exactly one nth
    // fire, and the set of match indices that fire under probability is the same as on one thread)
    @Test
    void decide_underContention_countsExactly() throws Exception {
        Rule capped = new Rule("capped", Match.of("GET", "/capped"), FailBeforeForward.status(503)).withTimes(5);
        Rule nth = new Rule("nth", Match.of("GET", "/nth").withNth(12_345), FailBeforeForward.status(503));
        Rule prob = new Rule("prob", Match.of("GET", "/prob").withProbability(0.1), FailBeforeForward.status(503));
        Matcher m = matcher(42, capped, nth, prob);
        AtomicInteger cappedFires = new AtomicInteger();
        AtomicInteger nthFires = new AtomicInteger();
        Set<Long> probIndices = ConcurrentHashMap.newKeySet();
        Set<Long> nthIndices = ConcurrentHashMap.newKeySet();

        int threads = 24;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 1000; i++) {
                        if (m.decide("GET", "/capped") instanceof Decision.Fire) {
                            cappedFires.incrementAndGet();
                        }
                        if (m.decide("GET", "/nth") instanceof Decision.Fire f) {
                            nthFires.incrementAndGet();
                            nthIndices.add(f.matchIndex());
                        }
                        if (m.decide("GET", "/prob") instanceof Decision.Fire f) {
                            probIndices.add(f.matchIndex());
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertThat(cappedFires).hasValue(5);
        assertThat(nthFires).hasValue(1);
        assertThat(nthIndices).containsExactly(12_345L);

        Matcher sequential = matcher(42, capped, nth, prob);
        Set<Long> expected = new java.util.HashSet<>();
        for (int i = 0; i < threads * 1000; i++) {
            if (sequential.decide("GET", "/prob") instanceof Decision.Fire f) {
                expected.add(f.matchIndex());
            }
        }
        assertThat(probIndices).isEqualTo(expected);
    }
}
