package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/**
 * CI run 44669651166 (PR 33): CliTest probed a free port with ServerSocket(0), closed it, and the service's bind a
 * moment later found it taken ("Port 37675 is already in use"). Port 0 is served from the ephemeral range, which every
 * other fork's port-0 bind and outgoing connection draws from too. A port below every ephemeral range (Linux from 32768,
 * macOS from 49152) is never handed out by the kernel, so nothing else takes it between the probe and the bind.
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
}
