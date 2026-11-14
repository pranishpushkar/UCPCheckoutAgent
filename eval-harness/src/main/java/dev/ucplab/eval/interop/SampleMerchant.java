package dev.ucplab.eval.interop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The official sample merchant for one lane (H1 plan sections 4, 5, 7): two fresh database files in a temp folder, the
 * server started on a free port bound to 127.0.0.1 and waited for at {@code /.well-known/ucp}, and on close the process
 * stopped and the folder deleted, even after a failure. How the third-party code is started is a {@link Launcher}: the
 * real one runs the pinned clone's scripts, a test passes a fake. Nothing here logs a payment token.
 */
public final class SampleMerchant implements AutoCloseable {

    /** A running server. */
    public interface Server extends AutoCloseable {
        boolean alive();

        /** What is known of why it stopped, for the start-up message. */
        String describe();

        @Override
        void close();
    }

    /** How the sample's own scripts are run (or faked). */
    public interface Launcher {
        /** Creates the two database files (the sample's {@code import_csv.py}). */
        void importData(Path productsDb, Path transactionsDb) throws IOException;

        /** Starts the server on {@code 127.0.0.1:port}; it returns at once. */
        Server serve(Path productsDb, Path transactionsDb, int port, String secret) throws IOException;
    }

    private final Launcher launcher;
    private final Path folder;
    private final Duration startupTimeout;
    private final SampleDb db;
    private final int port;
    private final String secret = "interop-" + UUID.randomUUID();
    private Server server;

    private SampleMerchant(Launcher launcher, Path folder, Duration startupTimeout, SampleDb db, int port) {
        this.launcher = launcher;
        this.folder = folder;
        this.startupTimeout = startupTimeout;
        this.db = db;
        this.port = port;
    }

    /** Starts a merchant under a fresh folder inside {@code workRoot}. */
    public static SampleMerchant start(Launcher launcher, Path workRoot, Duration startupTimeout) throws IOException {
        Objects.requireNonNull(launcher, "launcher");
        Path folder = Files.createTempDirectory(Files.createDirectories(workRoot), "sample-");
        SampleMerchant m = null;
        try {
            Path products = folder.resolve("products.db");
            Path tx = folder.resolve("transactions.db");
            launcher.importData(products, tx);
            SampleDb db = new SampleDb(products, tx);    // refuses a changed schema before anything is served
            db.snapshotTemplate(folder.resolve("template"));
            m = new SampleMerchant(launcher, folder, startupTimeout, db, freePort());
            m.launch();
            return m;
        } catch (IOException | RuntimeException e) {
            if (m != null) {
                m.close();
            } else {
                deleteTree(folder);
            }
            throw e;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private void launch() throws IOException {
        server = launcher.serve(Path.of(folder.toString(), "products.db"), Path.of(folder.toString(), "transactions.db"),
                port, secret);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!server.alive()) {
                throw new IOException("the sample merchant exited before it answered: " + server.describe());
            }
            try {
                HttpResponse<Void> r = http.send(HttpRequest.newBuilder(baseUrl().resolve("/.well-known/ucp"))
                        .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not up yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for the sample merchant", e);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for the sample merchant", e);
            }
        }
        throw new IOException("the sample merchant did not answer /.well-known/ucp on 127.0.0.1:" + port + " within "
                + startupTimeout.toSeconds() + " s");
    }

    /** The sample's origin: where its profile is, and where the fault proxy forwards to. */
    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + port);
    }

    public SampleDb db() {
        return db;
    }

    /** The merchant's own record of its orders (I1's ground truth). */
    public List<SampleOrder> orders() {
        return db.orders();
    }

    public void setPrice(String itemId, long priceMinor) {
        db.setPrice(itemId, priceMinor);
    }

    public void setStock(String itemId, int quantity) {
        db.setStock(itemId, quantity);
    }

    /** Fresh files: the server is stopped, both files restored from the copies taken at start, the server started again. */
    public void reset() throws IOException {
        server.close();
        db.reset();
        launch();
    }

    @Override
    public void close() {
        if (server != null) {
            server.close();
        }
        deleteTree(folder);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // best effort: the folder is a temp folder
        }
    }
}
