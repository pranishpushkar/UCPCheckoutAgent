package dev.ucplab.eval.interop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runner's lifecycle (H1 C7) with a fake launcher: no Python, no third-party code. */
class SampleMerchantTest {

    @TempDir
    Path dir;

    /** Fake sample: fake files with the pinned shape, and a loopback HTTP server that answers the profile. */
    private static final class Fake implements SampleMerchant.Launcher {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final boolean answers;
        final boolean badSchema;

        Fake(boolean answers, boolean badSchema) {
            this.answers = answers;
            this.badSchema = badSchema;
        }

        @Override
        public void importData(Path products, Path tx) throws IOException {
            try {
                SampleDbTest.create(products, tx, badSchema ? "id text, other text" : "id text primary key, data json");
            } catch (SQLException e) {
                throw new IOException(e);
            }
        }

        @Override
        public SampleMerchant.Server serve(Path products, Path tx, int port, String secret) throws IOException {
            starts.incrementAndGet();
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            if (answers) {
                s.createContext("/.well-known/ucp", ex -> {
                    ex.sendResponseHeaders(200, 2);
                    ex.getResponseBody().write("{}".getBytes());
                    ex.close();
                });
            }
            s.start();
            return new SampleMerchant.Server() {
                public boolean alive() {
                    return true;
                }

                public String describe() {
                    return "fake";
                }

                public void close() {
                    stops.incrementAndGet();
                    s.stop(0);
                }
            };
        }
    }

    // Covers: R1, 13.1 (start waits for the profile, binds 127.0.0.1, close stops the server and deletes the folder)
    @Test
    void startsOnLoopback_andCloseStopsAndDeletes() throws Exception {
        Fake fake = new Fake(true, false);
        SampleMerchant m = SampleMerchant.start(fake, dir, Duration.ofSeconds(5));
        Path folder;
        try (var files = Files.list(dir)) {
            folder = files.findFirst().orElseThrow();
        }

        assertThat(m.baseUrl().getHost()).isEqualTo("127.0.0.1");
        assertThat(Files.exists(folder.resolve("transactions.db"))).isTrue();
        m.close();

        assertThat(fake.stops.get()).isEqualTo(1);
        assertThat(Files.exists(folder)).isFalse();
    }

    // Covers: 13.1 (a server that never answers: a clear message naming the port and the timeout, the process stopped and
    // the folder deleted)
    @Test
    void aServerThatNeverAnswers_failsClearly_andCleansUp() throws Exception {
        Fake fake = new Fake(false, false);

        assertThatThrownBy(() -> SampleMerchant.start(fake, dir, Duration.ofMillis(600)))
                .isInstanceOf(IOException.class).hasMessageContaining("did not answer").hasMessageContaining("127.0.0.1");

        assertThat(fake.stops.get()).isEqualTo(1);
        try (var files = Files.list(dir)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    // Covers: R1 (a changed schema stops the start before anything is served, and the folder is deleted)
    @Test
    void aChangedSchema_refusesToStart_andNothingIsServed() throws Exception {
        Fake fake = new Fake(true, true);

        assertThatThrownBy(() -> SampleMerchant.start(fake, dir, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pinned");

        assertThat(fake.starts.get()).isZero();
        try (var files = Files.list(dir)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    // Covers: 13.1 (reset restarts the server on the same port over fresh files)
    @Test
    void reset_restartsOverFreshFiles() throws Exception {
        Fake fake = new Fake(true, false);
        try (SampleMerchant m = SampleMerchant.start(fake, dir, Duration.ofSeconds(5))) {
            URIHolder before = new URIHolder(m.baseUrl().toString());
            m.setPrice("bouquet_roses", 1);

            m.reset();

            assertThat(m.baseUrl().toString()).isEqualTo(before.value());
            assertThat(m.db().price("bouquet_roses")).isEqualTo(3500);
            assertThat(fake.starts.get()).isEqualTo(2);
        }
    }

    private record URIHolder(String value) { }
}
