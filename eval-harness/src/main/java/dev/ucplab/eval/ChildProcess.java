package dev.ucplab.eval;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * One agent child JVM ({@link AgentMain}) for S9 and S28 (m4-plan section 8). It runs this JVM's own {@code java}
 * binary with this JVM's classpath (m4-plan risk 1, checked by {@code ChildJvmTest}); its stdout and stderr go to one
 * log file, which the run's evidence keeps. Every wait is a poll with a deadline.
 */
final class ChildProcess implements AutoCloseable {

    /**
     * At most this many child JVMs at once across all lanes (m4-plan section 8 said 8). 16, one per lane of eval-full:
     * a child peaked at about 91 MB RSS (S9 and S28, 20 runs each at k = 1 and k = 4), so 16 are about 1.5 GB, and the
     * simulated eval-full is about 34 min at 16 against about 57 min at 8, past its 40 min budget (m4-progress5.md).
     */
    static final int MAX_CHILDREN = 16;
    /** SIGKILL is signal 9; a JVM killed by a signal exits with 128 plus the signal. */
    static final int SIGKILL_EXIT = 137;

    private static final Duration POLL = Duration.ofMillis(10);
    private static final Duration EXIT_LIMIT = Duration.ofSeconds(30);

    private final Process process;
    private final Path log;

    private ChildProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    /**
     * Starts {@link AgentMain} on {@code db}, pointed at {@code merchantUrl}, at time scale {@code scale}.
     *
     * @param log the file the child's stdout and stderr go to (created or replaced)
     */
    static ChildProcess startAgent(Path db, URI merchantUrl, TimeScale scale, long seed, Path log)
            throws IOException {
        String java = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IOException("the path of this JVM's java binary is not known"));
        List<String> command = new ArrayList<>(List.of(java, "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                "-Xmx128m", "-cp", System.getProperty("java.class.path"), AgentMain.class.getName(),
                "--db", db.toString(), "--merchant-url", merchantUrl.toString(),
                "--time-scale", Integer.toString(scale.k()), "--seed", Long.toString(seed)));
        Process p = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile())).start();
        return new ChildProcess(p, log);
    }

    long pid() {
        return process.pid();
    }

    boolean alive() {
        return process.isAlive();
    }

    /**
     * Waits for the child's {@code READY <worker id>} line and returns the worker id.
     *
     * @throws IOException if the child exits first, or prints nothing within {@code limit}
     */
    String awaitReady(Duration limit) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(limit);
        while (true) {
            Optional<String> ready = log().lines().filter(l -> l.startsWith(AgentMain.READY + " ")).findFirst();
            if (ready.isPresent()) {
                return ready.get().substring(AgentMain.READY.length() + 1).trim();
            }
            if (!process.isAlive()) {
                throw new IOException("the agent child exited " + process.exitValue() + " before it was ready");
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IOException("the agent child was not ready within " + limit);
            }
            Thread.sleep(POLL);
        }
    }

    /**
     * kill -9: {@link Process#destroyForcibly()}, which on macOS and Linux sends SIGKILL, then waits for the exit and
     * returns its value ({@value #SIGKILL_EXIT} for SIGKILL).
     *
     * <p>TODO(VERIFY): the {@code Process} javadoc promises only that a process from {@code ProcessBuilder.start()} is
     * "forcibly terminated", and {@code ProcessHandleImpl.destroy0} documents "SIGKILL vs SIGTERM"; the exit value of
     * 128 + 9 comes from native code that src.zip does not hold. The S9 and S28 tests assert 137, which checks it on
     * macOS; Linux CI is unchecked until the tests run there.
     */
    int kill() throws IOException, InterruptedException {
        process.destroyForcibly();
        return awaitExit();
    }

    /** Ends the child normally (SIGTERM, so its shutdown hook closes the database) and returns its exit value. */
    int stop() throws IOException, InterruptedException {
        process.destroy();
        return awaitExit();
    }

    private int awaitExit() throws IOException, InterruptedException {
        if (!process.waitFor(EXIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IOException("the agent child did not exit within " + EXIT_LIMIT);
        }
        return process.exitValue();
    }

    /** Everything the child has printed so far. */
    String log() throws IOException {
        return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    }

    /** Kills the child if it is still running (an aborted run); a no-op once it has exited. */
    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(EXIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
