package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** H3 P8: the limits of the JDK transport (8.3), per mutant. */
class JdkHttpTransportMutationTest {

    private static final int CAP = 100;
    private HttpServer server;
    private JdkHttpTransport transport;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        for (int size : new int[] {CAP - 1, CAP, CAP + 1}) {
            server.createContext("/" + size, ex -> {
                ex.sendResponseHeaders(200, size);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write("x".repeat(size).getBytes());
                }
            });
        }
        server.start();
        UcpConfig d = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
        transport = new JdkHttpTransport(new UcpConfig(d.ucpAgent(), d.connectTimeout(), d.requestTimeout(), d.completeTimeout(),
                d.maxRetries(), d.backoffInitial(), d.backoffCap(), d.breakerThreshold(), d.breakerOpenFor(), CAP));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpCall get(String path) {
        return new HttpCall("GET", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path), Map.of(), Optional.empty(),
                Duration.ofSeconds(5));
    }

    // Covers: 8.3 (a body of exactly the cap is read; one byte more is an I/O failure, hence Uncertain). Kills
    // JdkHttpTransport.send:61 ConditionalsBoundary
    @Test
    void aBodyOfExactlyTheCap_isRead_oneByteMoreIsNot() throws IOException {
        assertThat(transport.send(get("/" + (CAP - 1))).body()).hasSize(CAP - 1);
        assertThat(transport.send(get("/" + CAP)).body()).hasSize(CAP);
        assertThatThrownBy(() -> transport.send(get("/" + (CAP + 1)))).isInstanceOf(IOException.class)
                .hasMessageContaining("larger than " + CAP);
    }

    // Covers: 8.3, 7.8 (a call that is interrupted ends as an I/O failure that names the interruption as its cause and keeps the
    // thread's interrupt flag). Kills JdkHttpTransport.send:67 VoidMethodCall (NO_COVERAGE: initCause)
    @Test
    void anInterruptedCall_isAnInterruptedIOException_withItsCause_andTheFlagKept() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> transport.send(get("/" + CAP))).isInstanceOf(InterruptedIOException.class)
                    .hasMessageContaining("interrupted while calling /" + CAP).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
