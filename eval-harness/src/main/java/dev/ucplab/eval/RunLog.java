package dev.ucplab.eval;

import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Captures what is logged through JUL on a run's threads into that run's {@code run.log} (section 11, m5-plan section
 * 11), so the redaction test can search it. A run's threads are the lane thread that drives it and every thread
 * started from it while the capture is open (the recovery loop, a fault window's clearer, the HTTP client's own
 * threads): the run is held in an {@link InheritableThreadLocal}, and one handler on the root logger appends each record
 * to the run of the thread that logged it. Records from threads of no run are ignored here. The text is kept up to
 * about {@link #MAX_CHARS}.
 */
final class RunLog implements AutoCloseable {

    static final int MAX_CHARS = 1_000_000;
    private static final InheritableThreadLocal<RunLog> CURRENT = new InheritableThreadLocal<>();
    private static final Handler HANDLER = new Handler() {
        private final SimpleFormatter format = new SimpleFormatter();

        @Override
        public void publish(LogRecord record) {
            RunLog run = CURRENT.get();
            if (run != null && isLoggable(record)) {
                run.append(format.format(record));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    static {
        HANDLER.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(HANDLER);
    }

    private final StringBuffer text = new StringBuffer();

    private RunLog() {
    }

    /** Opens the capture for the calling thread and the threads it starts until {@link #close}. */
    static RunLog open() {
        RunLog log = new RunLog();
        CURRENT.set(log);
        return log;
    }

    private void append(String line) {
        Objects.requireNonNull(line, "line");
        // StringBuffer appends atomically; with threads racing, the cap may be passed by one record.
        if (text.length() < MAX_CHARS) {
            text.append(line);
        }
    }

    String text() {
        return text.toString();
    }

    /** The calling thread stops capturing; threads it started keep their reference until they end. */
    @Override
    public void close() {
        CURRENT.remove();
    }
}
