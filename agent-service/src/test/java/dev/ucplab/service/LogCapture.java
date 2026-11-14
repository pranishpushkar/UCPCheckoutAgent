package dev.ucplab.service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Everything the JVM writes to stdout and stderr while it is open, still passed on to the real streams. The service's
 * structured logging writes to {@code System.out}, so this sees exactly the lines {@code docker compose logs} would
 * show, after the redaction customiser (I8, C16). Start it before the service, so its console appender writes here.
 */
final class LogCapture implements AutoCloseable {

    private final PrintStream out = System.out;
    private final PrintStream err = System.err;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    LogCapture() {
        System.setOut(new PrintStream(new Tee(out, captured), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(err, captured), true, StandardCharsets.UTF_8));
    }

    /** Everything captured so far. */
    synchronized String text() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    List<String> lines() {
        return text().lines().toList();
    }

    @Override
    public void close() {
        System.setOut(out);
        System.setErr(err);
    }

    private static final class Tee extends OutputStream {
        private final OutputStream first;
        private final ByteArrayOutputStream second;

        Tee(OutputStream first, ByteArrayOutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void write(int b) {
            synchronized (second) {
                second.write(b);
            }
            try {
                first.write(b);
            } catch (java.io.IOException e) {
                // The real stream failing is not this test's concern.
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            synchronized (second) {
                second.write(b, off, len);
            }
            try {
                first.write(b, off, len);
            } catch (java.io.IOException e) {
                // As above.
            }
        }
    }
}
