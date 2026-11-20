package dev.ucplab.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A port for a test service that must know its own port before it starts (its PUBLIC_ORIGIN is its base URL). It is
 * below every ephemeral range (Linux from 32768, macOS from 49152), which port-0 binds and outgoing connections draw
 * from, so the kernel never gives it to another socket between this probe and the service's bind (CI run 44669651166).
 */
final class FreePort {

    static final int FIRST = 20_000;
    static final int COUNT = 10_000;

    private FreePort() {
    }

    /** A port in 20000 to 29999 that nothing listens on: the first free one from a random start, in turn. */
    static int pick() {
        int start = ThreadLocalRandom.current().nextInt(COUNT);
        for (int i = 0; i < COUNT; i++) {
            int port = FIRST + (start + i) % COUNT;
            try (ServerSocket s = new ServerSocket()) {
                s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                return port;
            } catch (IOException taken) {
                // something listens on it already; the next one
            }
        }
        throw new IllegalStateException("no free port in " + FIRST + " to " + (FIRST + COUNT - 1));
    }
}
