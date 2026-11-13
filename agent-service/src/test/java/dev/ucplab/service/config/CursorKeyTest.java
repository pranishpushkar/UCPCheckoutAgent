package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.crypto.HmacSha256;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentCursor;
import dev.ucplab.core.intent.IntentCursorCodec;
import dev.ucplab.core.intent.IntentId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cursor key (m6-plan Stage 2 review, point 1): HMAC-SHA256 of the approval secret with the label
 * {@code intent-cursor/v1}. No new secret, so a cursor survives a restart; and not the approval secret itself.
 */
class CursorKeyTest {

    private static final String SECRET = "cursor-test-secret-0123456789abcdefghij";
    private static final UserId ALICE = new UserId("local-alice");
    private static final IntentCursor CURSOR =
            new IntentCursor(Instant.parse("2026-11-14T10:00:00Z"), new IntentId("i-1"));

    @TempDir
    Path dir;

    private IntentCursorCodec codec(String secret) {
        ServiceProperties p = ServiceProperties.from(Environment.of(Map.of("APPROVAL_SECRET", secret,
                "DATABASE_URL", "jdbc:sqlite:" + dir.resolve("c.db"))));
        return new StackConfiguration().intentCursorCodec(p);
    }

    // Covers: 23.2, m6-plan review point 1 (a cursor from one start decodes in the next with the same secret)
    @Test
    void cursor_survivesARestartWithTheSameSecret() {
        String token = codec(SECRET).encode(ALICE, Optional.empty(), CURSOR);

        assertThat(codec(SECRET).decode(ALICE, Optional.empty(), token)).contains(CURSOR);
        assertThat(codec(SECRET + "-other").decode(ALICE, Optional.empty(), token)).isEmpty();
    }

    // Covers: m6-plan review point 1 (the key is HMAC(secret, "intent-cursor/v1"), not the secret itself)
    @Test
    void cursorKey_isTheLabelledHmacOfTheSecret() {
        byte[] secret = SECRET.getBytes(StandardCharsets.UTF_8);
        IntentCursorCodec derived = new IntentCursorCodec(
                HmacSha256.compute(secret, "intent-cursor/v1".getBytes(StandardCharsets.US_ASCII)));
        IntentCursorCodec raw = new IntentCursorCodec(secret);
        String token = codec(SECRET).encode(ALICE, Optional.empty(), CURSOR);

        assertThat(derived.decode(ALICE, Optional.empty(), token)).contains(CURSOR);
        assertThat(raw.decode(ALICE, Optional.empty(), token)).isEmpty();
    }
}
