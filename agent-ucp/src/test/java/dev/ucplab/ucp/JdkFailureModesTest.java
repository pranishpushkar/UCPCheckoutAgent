package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.FrozenBody;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What {@code java.net.http} actually raises when a raw socket server drops a connection in each way (8.1). The
 * exception types differ between platforms: on Linux a connection reset during connect is a {@code ConnectException},
 * and a peer that does not speak TLS gives a plain {@code SSLException}. So the tests that depend on the platform assert
 * what {@link FailureClassifier} makes of whatever is raised, never the type. The reused-connection tests pin a JDK
 * behaviour that held on both platforms: a pooled connection dropped after the request is a plain {@code IOException}.
 */
class JdkFailureModesTest {

    enum Drop { FIN_AFTER_REQUEST, RST_AFTER_REQUEST, FIN_AFTER_RESPONSE, RST_AFTER_RESPONSE }

    /** The outcome of the second request of a pair, sent after the first left a keep-alive connection pooled. */
    private record Observed(Optional<IOException> thrown, int replyStatus, int connections, int requestsTotal,
            int requestsOnFirstConnection) {}

    private static final FailureClassifier CLASSIFIER = new FailureClassifier(Clock.systemUTC());

    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://a.example/.well-known/ucp\"");

    private static Observed reusedConnection(String method, Drop drop) throws Exception {
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger onFirst = new AtomicInteger();
        AtomicInteger total = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Socket s = server.accept();
                        int n = connections.incrementAndGet();
                        Thread.ofVirtual().start(() -> serve(s, n, drop, onFirst, total));
                    }
                } catch (IOException ignored) {
                    // closed
                }
            });
            JdkHttpTransport t = new JdkHttpTransport(CONFIG);
            URI uri = URI.create("http://127.0.0.1:" + server.getLocalPort() + "/x");
            Optional<FrozenBody> body = method.equals("POST") ? Optional.of(new FrozenBody("{\"a\":1}")) : Optional.empty();
            assertThat(t.send(new HttpCall(method, uri, Map.of(), body, Duration.ofSeconds(2))).status()).isEqualTo(200);
            Thread.sleep(150);
            Optional<IOException> thrown = Optional.empty();
            int status = 0;
            try {
                status = t.send(new HttpCall(method, uri, Map.of(), body, Duration.ofSeconds(2))).status();
            } catch (IOException e) {
                thrown = Optional.of(e);
            }
            Thread.sleep(100);
            acceptor.interrupt();
            return new Observed(thrown, status, connections.get(), total.get(), onFirst.get());
        }
    }

    private static void serve(Socket s, int n, Drop drop, AtomicInteger onFirst, AtomicInteger total) {
        try (s) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (readRequest(in)) {
                total.incrementAndGet();
                int seen = n == 1 ? onFirst.incrementAndGet() : 0;
                if (n == 1 && seen == 2) {
                    // The second request arrived on the first, reused connection.
                    if (drop == Drop.RST_AFTER_REQUEST) {
                        s.setSoLinger(true, 0);
                    }
                    return;
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                if (n == 1 && (drop == Drop.FIN_AFTER_RESPONSE || drop == Drop.RST_AFTER_RESPONSE)) {
                    if (drop == Drop.RST_AFTER_RESPONSE) {
                        s.setSoLinger(true, 0);
                    }
                    return;
                }
            }
        } catch (IOException ignored) {
            // the client went away
        }
    }

    /** Reads one request (headers and a Content-Length body). False at end of stream. */
    private static boolean readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            byte[] h = head.toByteArray();
            int n = h.length;
            if (n >= 4 && h[n - 4] == '\r' && h[n - 3] == '\n' && h[n - 2] == '\r' && h[n - 1] == '\n') {
                String text = new String(h, StandardCharsets.US_ASCII).toLowerCase();
                int i = text.indexOf("content-length:");
                if (i >= 0) {
                    in.readNBytes(Integer.parseInt(text.substring(i + 15, text.indexOf("\r\n", i)).trim()));
                }
                return true;
            }
        }
        return false;
    }

    private static List<Throwable> chain(Throwable t) {
        List<Throwable> out = new ArrayList<>();
        for (Throwable c = t; c != null; c = c.getCause()) {
            out.add(c);
        }
        return out;
    }

    private static void assertNoConnectException(Throwable t) {
        assertThat(chain(t)).noneMatch(c -> c instanceof ConnectException);
    }

    // Covers: 8.1, decision A (a POST whose request was written on a reused connection that the server then closes or
    // resets fails with a plain IOException, never ConnectException: the request reached the server, so the
    // outcome is Uncertain)
    @ParameterizedTest
    @EnumSource(value = Drop.class, names = {"FIN_AFTER_REQUEST", "RST_AFTER_REQUEST"})
    void post_onReusedConnectionDroppedAfterRequest_throwsPlainIOException_notConnectException(Drop drop) throws Exception {
        Observed o = reusedConnection("POST", drop);

        assertThat(o.thrown()).isPresent();
        IOException e = o.thrown().get();
        assertThat(e).isNotInstanceOf(ConnectException.class).hasMessageContaining("header parser received no bytes");
        assertNoConnectException(e);
        assertThat(chain(e)).anyMatch(c -> drop == Drop.FIN_AFTER_REQUEST
                ? c instanceof EOFException : c instanceof SocketException);
        // The request bytes did arrive on the reused connection, so "not sent" would have been false.
        assertThat(o.requestsOnFirstConnection()).isEqualTo(2);
        assertThat(o.connections()).isEqualTo(1);
    }

    // Covers: 8.1, decision A (a pooled connection that died while idle is noticed before the request is written: the
    // JDK opens a new connection and the call succeeds, so no exception reaches the gateway at all)
    @ParameterizedTest
    @EnumSource(value = Drop.class, names = {"FIN_AFTER_RESPONSE", "RST_AFTER_RESPONSE"})
    void post_onReusedConnectionDroppedWhileIdle_succeedsOnANewConnection(Drop drop) throws Exception {
        Observed o = reusedConnection("POST", drop);

        assertThat(o.thrown()).isEmpty();
        assertThat(o.replyStatus()).isEqualTo(200);
        assertThat(o.connections()).isEqualTo(2);
        assertThat(o.requestsOnFirstConnection()).isEqualTo(1);
    }

    // Covers: 8.1, 8.2 (the JDK itself re-sends an idempotent GET on a new connection after a drop, so a GET shows the
    // merchant two requests where the gateway made one; harmless for a read)
    @ParameterizedTest
    @EnumSource(value = Drop.class, names = {"FIN_AFTER_REQUEST", "RST_AFTER_REQUEST"})
    void get_onReusedConnectionDroppedAfterRequest_isResentByTheJdk(Drop drop) throws Exception {
        Observed o = reusedConnection("GET", drop);

        assertThat(o.thrown()).isEmpty();
        assertThat(o.replyStatus()).isEqualTo(200);
        assertThat(o.requestsTotal()).isEqualTo(3);
        assertThat(o.connections()).isEqualTo(2);
    }

    /** A fresh connection that the server accepts and drops before reading anything. */
    private static IOException freshDrop(String method, boolean rst, int bodyBytes) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Socket s = server.accept();
                        if (rst) {
                            s.setSoLinger(true, 0);
                        }
                        s.close();
                    }
                } catch (IOException ignored) {
                    // closed
                }
            });
            Optional<FrozenBody> body = method.equals("POST")
                    ? Optional.of(new FrozenBody("{\"a\":\"" + "x".repeat(bodyBytes) + "\"}")) : Optional.empty();
            try {
                new JdkHttpTransport(CONFIG).send(new HttpCall(method,
                        URI.create("http://127.0.0.1:" + server.getLocalPort() + "/x"), Map.of(), body,
                        Duration.ofSeconds(3)));
                throw new AssertionError("expected an IOException");
            } catch (IOException e) {
                return e;
            } finally {
                acceptor.interrupt();
            }
        }
    }

    /** Whatever the platform raised, a mutating call that failed this way is Uncertain: it may have been written. */
    private static void assertUncertainForMutatingOps(Throwable failure) {
        for (AttemptOp op : AttemptOp.values()) {
            if (op != AttemptOp.GET) {
                assertThat(CLASSIFIER.classify(op, failure)).as("%s after %s", op, failure)
                        .isInstanceOf(CallOutcome.Uncertain.class);
            }
        }
    }

    // Covers: 8.1 (a connection accepted and then closed or reset before any reply, with a small or a 4 MB body, is
    // Uncertain for every mutating call, whichever exception type the platform raises: the JDK reports a plain
    // IOException on macOS and a ConnectException "Connection reset by peer (connect failed)" on Linux)
    @Test
    void freshConnectionDropped_isUncertain_forEveryMutatingOp() throws Exception {
        for (String method : List.of("POST", "GET")) {
            for (boolean rst : new boolean[] {false, true}) {
                assertUncertainForMutatingOps(freshDrop(method, rst, 10));
            }
        }
        assertUncertainForMutatingOps(freshDrop("POST", true, 4_000_000));
        assertUncertainForMutatingOps(freshDrop("POST", false, 4_000_000));
    }

    // Covers: 8.1 (connection refused is a ConnectException; the classifier still treats it as Uncertain, because the
    // type alone does not prove that nothing was written)
    @Test
    void connectionRefused_isAConnectException() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }

        try {
            new JdkHttpTransport(CONFIG).send(new HttpCall("POST", URI.create("http://127.0.0.1:" + closed + "/x"),
                    Map.of(), Optional.of(new FrozenBody("{}")), Duration.ofSeconds(2)));
            throw new AssertionError("expected a ConnectException");
        } catch (ConnectException expected) {
            assertThat(expected).isNotNull();
        }
    }

    // Covers: 8.1 (a name that does not resolve is a ConnectException caused by UnresolvedAddressException: the one
    // ConnectException that is NotSent)
    @Test
    void unresolvableHost_isAConnectException_causedByUnresolvedAddress() throws Exception {
        try {
            new JdkHttpTransport(CONFIG).send(new HttpCall("POST", URI.create("http://no-such-host.invalid:81/x"),
                    Map.of(), Optional.of(new FrozenBody("{}")), Duration.ofSeconds(3)));
            throw new AssertionError("expected a ConnectException");
        } catch (ConnectException e) {
            assertThat(chain(e)).anyMatch(c -> c instanceof UnresolvedAddressException);
            for (AttemptOp op : AttemptOp.values()) {
                assertThat(CLASSIFIER.classify(op, e)).as(op.name()).isInstanceOf(CallOutcome.NotSent.class);
            }
        }
    }

    // Covers: 8.1 (a TLS handshake that the peer ends, or a peer that does not speak TLS, is an SSLHandshakeException
    // or, on Linux for a plaintext reply, a plain SSLException "Unrecognized SSL message"; either way a mutating call
    // is Uncertain)
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"close", "garbage", "http-reply"})
    void tlsHandshakeFailure_isAnSSLException_andUncertainForMutatingOps(String mode) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Socket s = server.accept();
                        if (!mode.equals("close")) {
                            s.getInputStream().read(new byte[64]);
                            s.getOutputStream().write(mode.equals("garbage")
                                    ? new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
                                    : "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n"
                                            .getBytes(StandardCharsets.US_ASCII));
                            s.getOutputStream().flush();
                        }
                        s.close();
                    }
                } catch (IOException ignored) {
                    // closed
                }
            });
            try {
                new JdkHttpTransport(CONFIG).send(new HttpCall("POST",
                        URI.create("https://127.0.0.1:" + server.getLocalPort() + "/x"), Map.of(),
                        Optional.of(new FrozenBody("{}")), Duration.ofSeconds(3)));
                throw new AssertionError("expected an SSLException");
            } catch (SSLException expected) {
                assertUncertainForMutatingOps(expected);
            } finally {
                acceptor.interrupt();
            }
        }
    }
}
