package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The I8 canary reaches the agent child JVM (m6-plan section 10, C16, carried from M5): {@link ChildProcess} hands it
 * over in the environment, {@link AgentMain} plants it in the child's approval secret as {@link AgentStack} does in
 * process. The child prints a SHA-256 of the planted part on request, which a test compares with the canary's own, so
 * the canary itself is never printed.
 */
class ChildCanaryTest {

    private static final TimeScale SCALE = TimeScale.of(4);

    @TempDir
    Path dir;

    private String fingerprintOfChild(Lane lane, String name, String canary) throws Exception {
        Path log = dir.resolve(name + ".log");
        ChildProcess child = ChildProcess.startAgent(lane.newDatabase(name), lane.agentFacingUrl(), SCALE, 1, log,
                canary, true);
        try {
            child.awaitReady(Duration.ofSeconds(60));
            String out = child.log();
            assertThat(out).as("the child's own output").doesNotContain(canary);
            return out.lines().filter(l -> l.startsWith(AgentMain.FINGERPRINT + " ")).findFirst().orElseThrow()
                    .substring(AgentMain.FINGERPRINT.length() + 1).trim();
        } finally {
            child.stop();
        }
    }

    // Covers: I8, 11 (a child JVM's approval secret holds the evaluation's canary: its fingerprint is the canary's, and a
    // child given another canary has another; the canary is in no output of the child)
    @Test
    void childSecret_holdsTheEvaluationsCanary() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String canary = Canary.generate();
        String other = Canary.generate();
        try (Lane lane = Lane.start(0, key, dir)) {
            String held = fingerprintOfChild(lane, "a", canary);
            String different = fingerprintOfChild(lane, "b", other);

            assertThat(held).isEqualTo(AgentStack.fingerprintOf(canary));
            assertThat(different).isEqualTo(AgentStack.fingerprintOf(other)).isNotEqualTo(held);
        }
    }
}
