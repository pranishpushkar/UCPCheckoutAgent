package dev.ucplab.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * A port for a test service that must know its own port before it starts (its PUBLIC_ORIGIN is its base URL).
 */
final class FreePort {

    private FreePort() {
    }

    /** A port nothing listens on. */
    static int pick() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
