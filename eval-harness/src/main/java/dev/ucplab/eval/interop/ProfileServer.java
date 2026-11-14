package dev.ucplab.eval.interop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stub of the agent's own profile (H1 plan section 5): the sample merchant fetches the URL in {@code UCP-Agent} on a
 * create and an update, and the harness serves it on loopback so nothing reaches the network. It counts the fetches, which
 * is the evidence of how often the sample fetches it.
 */
public final class ProfileServer implements AutoCloseable {

    private static final String BODY = "{\"ucp\":{\"version\":\"2026-04-08\",\"capabilities\":{"
            + "\"dev.ucp.shopping.checkout\":[{\"version\":\"2026-04-08\"}]}}}";

    private final HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    private ProfileServer(HttpServer server) {
        this.server = server;
    }

    public static ProfileServer start() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ProfileServer p = new ProfileServer(s);
        s.createContext("/", p::handle);
        s.start();
        return p;
    }

    private void handle(HttpExchange ex) throws IOException {
        hits.add(ex.getRequestURI().getPath());
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** The {@code UCP-Agent} header value for this server. */
    public String ucpAgent() {
        return "profile=\"http://127.0.0.1:" + server.getAddress().getPort() + "/agent\"";
    }

    /** How many times the sample fetched the profile. */
    public int fetches() {
        return hits.size();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
