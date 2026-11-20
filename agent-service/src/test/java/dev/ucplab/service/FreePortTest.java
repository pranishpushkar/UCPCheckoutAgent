package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * CI run 44669651166 (PR 33): CliTest probed a free port with ServerSocket(0), closed it, and the service's bind a
 * moment later found it taken ("Port 37675 is already in use"). Port 0 is served from the ephemeral range, which every
 * other fork's port-0 bind and outgoing connection draws from too. A port below every ephemeral range (Linux from 32768,
 * macOS from 49152) is never handed out by the kernel; and each surefire fork picks only within its own block, from
 * surefire's fork number, so two forks can never pick the same port.
 */
class FreePortTest {

    // Covers: 8, 24.1 (the test service's port is below every ephemeral range, in 20000 to 29999, and nothing listens on it)
    @Test
    void aPickedPort_isBelowEveryEphemeralRange_andFree() throws IOException {
        for (int i = 0; i < 20; i++) {
            int port = FreePort.pick();

            assertThat(port).isBetween(20_000, 29_999);
            try (ServerSocket s = new ServerSocket()) {
                s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                assertThat(s.getLocalPort()).isEqualTo(port);
            }
        }
    }

    // Covers: 8, 24.1 (each fork's block is 20000 + forkNumber x 10, 10 ports; for the configured fork count the blocks are
    // pairwise disjoint and inside 20000 to 29999. Surefire numbers forks from 1 to forkCount x Maven's -T threads, across
    // the whole build: CI runs -T 1C with forkCount 1C, so up to cores x cores)
    @Test
    void theForksBlocks_doNotOverlap_forTheConfiguredForkCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        int forks = cores * cores;
        assertThat(forks).as("fork numbers a -T 1C build with forkCount 1C can give").isLessThanOrEqualTo(FreePort.MAX_FORKS);

        List<int[]> blocks = new ArrayList<>();
        for (int fork = 1; fork <= forks; fork++) {
            int first = FreePort.blockStart(fork);
            int last = first + FreePort.BLOCK - 1;
            assertThat(first).as("fork %d's first port", fork).isGreaterThanOrEqualTo(20_000);
            assertThat(last).as("fork %d's last port", fork).isLessThanOrEqualTo(29_999);
            for (int[] other : blocks) {
                assertThat(last < other[0] || first > other[1]).as("fork %d's block against %d to %d", fork, other[0],
                        other[1]).isTrue();
            }
            blocks.add(new int[] {first, last});
        }
        assertThat(FreePort.blockStart(1)).isEqualTo(20_010);
        assertThat(FreePort.blockStart(2)).isEqualTo(20_020);
    }

    // Covers: 8, 24.1 (surefire tells this fork its number, so the pick is from this fork's own block)
    @Test
    void aPick_isInThisForksOwnBlock() {
        String fork = System.getProperty(FreePort.FORK_PROPERTY);
        assertThat(fork).as("the pom passes surefire's fork number to each fork").isNotNull().matches("[0-9]+");
        int first = FreePort.blockStart(Integer.parseInt(fork));

        assertThat(FreePort.pick()).isBetween(first, first + FreePort.BLOCK - 1);
    }

    // Covers: 8 (a fork number whose block would leave 20000 to 29999 is refused, never wrapped into another block)
    @Test
    void aForkNumberPastTheRange_isRefused() {
        assertThat(FreePort.blockStart(FreePort.MAX_FORKS) + FreePort.BLOCK - 1).isLessThanOrEqualTo(29_999);
        assertThatThrownBy(() -> FreePort.blockStart(FreePort.MAX_FORKS + 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FreePort.blockStart(0)).isInstanceOf(IllegalStateException.class);
    }
}
