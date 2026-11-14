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
public final class ChildProcess implements AutoCloseable {

    /**
     * At most this many child JVMs at once across all lanes (m4-plan section 8 said 8). 16, one per lane of eval-full:
     * a child peaked at about 91 MB RSS (S9 and S28, 20 runs each at k = 1 and k = 4), so 16 are about 1.5 GB, and the
     * simulated eval-full is about 34 min at 16 against about 57 min at 8, past its 40 min budget (m4-progress5.md).
     */
    static final int MAX_CHILDREN = 16;
    /** SIGKILL is signal 9; a JVM killed by a signal exits with 128 plus the signal. */
    public static final int SIGKILL_EXIT = 137;
    /** The environment variable that carries the evaluation's canary to a child JVM. */
    static final String CANARY_ENV = "UCPLAB_CANARY";

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
     * @param canary the evaluation's I8 canary, handed to the child in {@value #CANARY_ENV} (never on the command line,
     *     where {@code ps} would show it), which plants it in the child's approval secret as the parent's stack does
     */
    static ChildProcess startAgent(Path db, URI merchantUrl, TimeScale scale, long seed, Path log, String canary)
            throws IOException {
        return startAgent(db, merchantUrl, scale, seed, log, canary, false);
    }

    /** As above; {@code probe} makes the child also print the canary's fingerprint ({@link AgentMain#FINGERPRINT}). */
    static ChildProcess startAgent(Path db, URI merchantUrl, TimeScale scale, long seed, Path log, String canary,
                                   boolean probe) throws IOException {
        return startAgent(db, merchantUrl, scale, seed, log, canary, probe, List.of());
    }

    /** As above, with {@code extra} arguments for {@link AgentMain} (for example the sample's wiring). */
    public static ChildProcess startAgent(Path db, URI merchantUrl, TimeScale scale, long seed, Path log, String canary,
                                   boolean probe, List<String> extra) throws IOException {
        String java = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IOException("the path of this JVM's java binary is not known"));
        List<String> command = new ArrayList<>(List.of(java, "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                "-Xmx128m", "-cp", System.getProperty("java.class.path"), AgentMain.class.getName(),
                "--db", db.toString(), "--merchant-url", merchantUrl.toString(),
                "--time-scale", Integer.toString(scale.k()), "--seed", Long.toString(seed)));
        command.addAll(extra);
        if (probe) {
            command.add("--print-canary-fingerprint");
            command.add("true");
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
        builder.environment().put(CANARY_ENV, canary);
        Process p = builder.start();
        return new ChildProcess(p, log);
    }

    long pid() {
        return process.pid();
    }

    public boolean alive() {
        return process.isAlive();
    }

    /**
     * Waits for the child's {@code READY <worker id>} line and returns the worker id.
     *
     * @throws IOException if the child exits first, or prints nothing within {@code limit}
     */
    public String awaitReady(Duration limit) throws IOException, InterruptedException {
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
     * <p>The {@code Process} javadoc promises only that a process from {@code ProcessBuilder.start()} is "forcibly
     * terminated", and {@code ProcessHandleImpl.destroy0} documents "SIGKILL vs SIGTERM"; the exit value of 128 + 9
     * comes from native code that src.zip does not hold. The S9 and S28 tests assert 137, and it held on macOS and on
     * Linux (CI run https://github.com/pranishpushkar/UCPCheckoutAgent/actions/runs/43337144864, ubuntu-26.04, Temurin 21.0.13).
     */
    public int kill() throws IOException, InterruptedException {
        process.destroyForcibly();
        return awaitExit();
    }

    /** Ends the child normally (SIGTERM, so its shutdown hook closes the database) and returns its exit value. */
    public int stop() throws IOException, InterruptedException {
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
