package dev.ucplab.eval;

import dev.ucplab.core.id.UuidV7IdGenerator;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/**
 * The agent child process that S9 and S28 kill with kill -9 (m4-plan section 8). It is a recovery worker on the run's
 * database file: the parent (the harness) proposes and approves, as the REST API process will in M6, and this process
 * takes the APPROVED intent on its next pass and drives it, sending the complete. Its worker id is new at every start
 * and comes from a UUIDv7 generator (Decision 4), as do every attempt and request id, so a restarted process never
 * repeats an id. The merchant and the proxy live in the parent, so they outlive the kill.
 *
 * <p>It prints exactly one line, {@code READY <worker id>}, once its loop is running, and nothing else of its own; it
 * never prints a body, a token or a header. It pays with {@link Scenarios#PAY_OK}, and has an approval secret of its
 * own: S9 and S28 never re-quote, so it never opens an approval.
 *
 * <pre>
 * AgentMain --db FILE --merchant-url URL --time-scale K --seed S
 * </pre>
 */
public final class AgentMain {

    static final String READY = "READY";
    /** {@code FINGERPRINT <hex>}: printed with {@code --print-canary-fingerprint true}, see {@link AgentStack}. */
    static final String FINGERPRINT = "FINGERPRINT";

    private AgentMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        Path db = null;
        URI merchant = null;
        int k = 0;
        long seed = 0;
        boolean fingerprint = false;
        String sampleAgent = null;
        try {
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(args[i] + " needs a value");
                }
                String v = args[i + 1];
                switch (args[i]) {
                    case "--db" -> db = Path.of(v);
                    case "--merchant-url" -> merchant = URI.create(v);
                    case "--time-scale" -> k = Integer.parseInt(v);
                    case "--seed" -> seed = Long.parseLong(v);
                    case "--print-canary-fingerprint" -> fingerprint = Boolean.parseBoolean(v);
                    case "--sample-ucp-agent" -> sampleAgent = v;
                    default -> throw new IllegalArgumentException("unknown argument " + args[i]);
                }
            }
            if (db == null || merchant == null || k == 0 || !Files.isRegularFile(db)) {
                throw new IllegalArgumentException("--db (an existing file), --merchant-url and --time-scale are"
                        + " required");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("AgentMain: " + e.getMessage());
            System.exit(2);
            return;
        }
        Clock clock = Clock.systemUTC();
        TimeScale scale = TimeScale.of(k);
        // The evaluation's canary, planted in the approval secret as the parent's stack plants it (I8, section 11).
        String canary = System.getenv(ChildProcess.CANARY_ENV);
        AgentStack stack = new AgentStack(merchant, db, scale, seed, new UuidV7IdGenerator(clock, new SecureRandom()),
                sampleAgent == null ? Scenarios.PAY_OK : dev.ucplab.eval.interop.SampleWiring.SUCCESS_TOKEN, scale.orchestrator().lease(),
                canary == null ? "" : canary, sampleAgent == null ? MerchantWiring.LAB : dev.ucplab.eval.interop.SampleWiring.of(sampleAgent));
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stack.close();
            stopped.countDown();
        }, "agent-shutdown"));
        stack.loop.start();
        if (fingerprint) {
            System.out.println(FINGERPRINT + " " + stack.canaryFingerprint());
        }
        System.out.println(READY + " " + stack.workerId());
        System.out.flush();
        stopped.await();
    }
}
