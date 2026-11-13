package dev.ucplab.adk.gemini;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A loopback stand-in for the Gemini API (C15): it records each request and answers with a status and body, or holds
 * the answer back until released (a timeout). {@link #dropping} is a raw socket that reads the request and closes the
 * connection without a reply. Nothing leaves 127.0.0.1.
 */
final class GeminiStub implements AutoCloseable {

    record Received(String method, String uri, String keyHeader, String body) {
    }

    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final HttpServer server;
    private volatile int status = 200;
    private volatile String reply = "{}";
    private volatile boolean hold;
    private volatile boolean echoKey;

    GeminiStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestHeaders().getFirst("x-goog-api-key");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().toString(), key, body));
            if (hold) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // The worst case for redaction: an error body that repeats the key, as a careless server might.
            String text = echoKey ? reply.replace("$KEY", String.valueOf(key)) : reply;
            byte[] out = text.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            try {
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
            } catch (IOException e) {
                // The client gave up (its timeout); nothing to answer.
            }
            ex.close();
        });
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    GeminiStub answer(int status, String body) {
        this.status = status;
        this.reply = body;
        return this;
    }

    /** Error bodies repeat the received key where they say {@code $KEY}. */
    GeminiStub echoingKey() {
        this.echoKey = true;
        return this;
    }

    /** Holds every answer until {@link #close}, so the client's call timeout passes first. */
    GeminiStub holding() {
        this.hold = true;
        return this;
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
    }

    /** A server that reads each request and closes the connection without a reply. */
    static final class Dropping implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread acceptor;

        Dropping() throws IOException {
            socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            acceptor = Thread.ofPlatform().daemon().start(() -> {
                while (!socket.isClosed()) {
                    try (Socket s = socket.accept()) {
                        InputStream in = s.getInputStream();
                        in.read(new byte[8192]);
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        URI url() {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }

        @Override
        public void close() throws IOException {
            socket.close();
            try {
                acceptor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static Dropping dropping() throws IOException {
        return new Dropping();
    }
}
