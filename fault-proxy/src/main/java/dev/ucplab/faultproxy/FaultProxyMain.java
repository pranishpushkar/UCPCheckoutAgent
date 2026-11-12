package dev.ucplab.faultproxy;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * Runs a standalone proxy for the scripts: {@code --port N --upstream URL [--rules file.json]}. The {@code bodyHmac} key
 * is generated here at start-up from {@link SecureRandom}, handed to the proxy and wiped; it is never printed, written
 * or derived from a seed (§12 Body hash).
 */
public final class FaultProxyMain {

    static final String USAGE = "usage: FaultProxyMain --port N --upstream http://127.0.0.1:PORT [--rules file.json]";

    private FaultProxyMain() {
    }

    record Args(int port, URI upstream, Optional<Path> rules) {
        Args {
            Objects.requireNonNull(upstream, "upstream");
            Objects.requireNonNull(rules, "rules");
            if (port < 0 || port > 65_535) {
                throw new IllegalArgumentException("--port must be 0 to 65535: " + port);
            }
        }

        /** @throws IllegalArgumentException on a missing, repeated or unknown flag, or a bad value */
        static Args parse(String[] args) {
            Integer port = null;
            URI upstream = null;
            Path rules = null;
            for (int i = 0; i < args.length; i++) {
                String flag = args[i];
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + flag);
                }
                String value = args[++i];
                switch (flag) {
                    case "--port" -> {
                        if (port != null) {
                            throw new IllegalArgumentException("--port given twice");
                        }
                        try {
                            port = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--port must be a number: " + value);
                        }
                    }
                    case "--upstream" -> {
                        if (upstream != null) {
                            throw new IllegalArgumentException("--upstream given twice");
                        }
                        upstream = URI.create(value);
                    }
                    case "--rules" -> {
                        if (rules != null) {
                            throw new IllegalArgumentException("--rules given twice");
                        }
                        rules = Path.of(value);
                    }
                    default -> throw new IllegalArgumentException("unknown flag " + flag);
                }
            }
            if (port == null || upstream == null) {
                throw new IllegalArgumentException("--port and --upstream are required");
            }
            return new Args(port, upstream, Optional.ofNullable(rules));
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        FaultProxy proxy;
        try {
            proxy = start(args, System.out);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::close));
        new CountDownLatch(1).await();
    }

    /** Starts the proxy, installs the rules file if one is given, and prints one line naming both addresses. */
    static FaultProxy start(String[] args, PrintStream out) throws IOException {
        Args parsed = Args.parse(args);
        Optional<RuleSet> rules = parsed.rules().isPresent()
                ? Optional.of(RuleSet.parse(Files.readString(parsed.rules().get())))
                : Optional.empty();
        byte[] key = newKey();
        FaultProxy proxy;
        try {
            proxy = FaultProxy.start(parsed.upstream(), parsed.port(), key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        rules.ifPresent(proxy::install);
        out.println("fault-proxy listening on " + proxy.baseUrl() + ", forwarding to " + parsed.upstream());
        return proxy;
    }

    /** 32 bytes from a fresh {@link SecureRandom}, never seeded by us. */
    static byte[] newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
