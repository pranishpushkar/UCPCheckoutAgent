package dev.ucplab.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * A port for a test service that must know its own port before it starts (its PUBLIC_ORIGIN is its base URL).
 *
 * <p>Two rules make it collision-free (CI run 44669651166). The port is below every ephemeral range (Linux from 32768,
 * macOS from 49152), which port-0 binds and outgoing connections draw from, so the kernel never gives it to another
 * socket. And each surefire fork picks only within its own block of {@value #BLOCK} ports, at 20000 + forkNumber x
 * {@value #BLOCK}, so no two forks can pick the same one. Surefire numbers forks from 1 to forkCount x Maven's -T
 * threads across the whole build, so the number is unique among every fork alive at once.
 */
final class FreePort {

    static final int FIRST = 20_000;
    static final int BLOCK = 10;
    /** The most forks whose blocks fit below 30000: fork 999's block is 29990 to 29999. */
    static final int MAX_FORKS = 999;
    /** Set by the root pom for every surefire fork (systemPropertyVariables); 1 outside surefire, such as in an IDE. */
    static final String FORK_PROPERTY = "ucplab.forkNumber";

    private FreePort() {
    }

    /** The first port of {@code fork}'s block; a fork number with no block below 30000 is refused. */
    static int blockStart(int fork) {
        if (fork < 1 || fork > MAX_FORKS) {
            throw new IllegalStateException("surefire fork " + fork + " has no port block: forks 1 to " + MAX_FORKS);
        }
        return FIRST + fork * BLOCK;
    }

    /** The first port in this fork's block that nothing listens on, in order. */
    static int pick() {
        int first = blockStart(Integer.parseInt(System.getProperty(FORK_PROPERTY, "1")));
        for (int port = first; port < first + BLOCK; port++) {
            try (ServerSocket s = new ServerSocket()) {
                s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                return port;
            } catch (IOException taken) {
                // something outside the build listens on it; the next one in this fork's block
            }
        }
        throw new IllegalStateException("no free port in " + first + " to " + (first + BLOCK - 1));
    }
}
