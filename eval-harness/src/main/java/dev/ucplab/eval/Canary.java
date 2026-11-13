package dev.ucplab.eval;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The I8 canary of an evaluation (section 11, m5-plan section 11): {@code canary-<32 hex>}, generated once per
 * evaluation and planted where a secret could leak: as every run's {@code AuthContext.sessionId} (a credential
 * reference) and appended to the approval secret's bytes. The redaction test then requires that it appears in no report,
 * evidence file, log, trace or captured output. The model-key canary (a scripted run's {@code ModelKey}) comes with
 * {@code GeminiModelClient} in C15.
 */
public final class Canary {

    public static final Pattern FORMAT = Pattern.compile("canary-[0-9a-f]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Canary() {
    }

    public static String generate() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return "canary-" + HexFormat.of().formatHex(b);
    }
}
