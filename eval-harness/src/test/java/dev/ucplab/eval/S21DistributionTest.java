package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.eval.check.CheckContext;
import dev.ucplab.eval.check.CheckResult;
import dev.ucplab.eval.check.Checks;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * S21 as a distribution (m6a-plan risk 8): the race is run {@code s21.runs} times (30 in verify; 200 for the stage's
 * reading, {@code -Ds21.runs=200}) in one lane of three, and the terminal states, the orders per run and the winner are
 * counted. A run that oversold, aborted, violated an invariant or left a state other than one winner and one
 * out_of_stock fails the test with its run number and seed.
 */
class S21DistributionTest {

    private static final TimeScale SCALE = TimeScale.of(4);
    private static final MerchantId B = Scenarios.SHOP_B;

    @TempDir
    Path dir;

    // Covers: S21, I1, 19 (every run of the race ends with one order and one winner; the winner varies by run)
    @Test
    void s21_distribution_everyRunOneOrderOneWinner() throws Exception {
        int runs = Integer.getInteger("s21.runs", 30);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Map<String, Integer> outcomes = new TreeMap<>();
        Map<Integer, Integer> ordersPerRun = new TreeMap<>();
        int firstUserWon = 0;
        try (Lane lane = Lane.start(0, key, dir, 3)) {
            RunScript script = new RunScript(lane, SCALE);
            CheckContext checks = CheckContext.of(SCALE, key, Scenarios.SHOP, RunScript.POLL);
            for (int run = 0; run < runs; run++) {
                long seed = Scenarios.seedIndex(Scenarios.S21) * 10_000L + run;
                RunEvidence e = script.run(Scenarios.S21, run, seed);
                CheckResult found = Checks.run(e, checks);
                int orders = e.views().stream().filter(v -> v.merchant().equals(B)).findFirst().orElseThrow()
                        .orders().size();
                ordersPerRun.merge(orders, 1, Integer::sum);
                String states = e.intents().stream().map(p -> p.intent().state().name()).sorted()
                        .reduce((a, b) -> a + " + " + b).orElse("none");
                outcomes.merge(states, 1, Integer::sum);
                if (!e.intents().isEmpty() && e.intents().getFirst().intent().state() == IntentState.COMPLETED) {
                    firstUserWon++;
                }
                assertThat(e.aborted()).as("run %d (seed %d) aborted", run, seed).isEmpty();
                assertThat(found.all()).as("run %d (seed %d) violations", run, seed).isEmpty();
                assertThat(orders).as("run %d (seed %d) orders at B", run, seed).isEqualTo(1);
            }
        }
        System.out.println("S21 DISTRIBUTION over " + runs + " runs: terminal states " + outcomes + "; orders per run "
                + ordersPerRun + "; the first user won " + firstUserWon + ", the second " + (runs - firstUserWon));
        assertThat(outcomes).containsOnlyKeys("COMPLETED + FAILED_NOT_CHARGED");
        assertThat(ordersPerRun).containsOnlyKeys(1);
    }
}
