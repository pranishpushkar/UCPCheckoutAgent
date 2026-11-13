package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.identity.UserId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The 23.2 paging cursor: opaque to the client, bound to the caller and the filter, and refused when tampered with. */
class IntentCursorCodecTest {

    private static final byte[] KEY = "intent-cursor-test-key-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final UserId ALICE = new UserId("alice");
    private static final UserId BOB = new UserId("bob");
    private static final IntentCursor AT = new IntentCursor(Instant.parse("2026-01-01T00:00:00.000000001Z"),
            new IntentId("018f-intent"));

    private final IntentCursorCodec codec = new IntentCursorCodec(KEY);

    // Covers: 23.2 (a cursor round-trips for the same caller and filter, to the nanosecond)
    @Test
    void encode_thenDecode_roundTrips() {
        String token = codec.encode(ALICE, Optional.empty(), AT);

        assertThat(codec.decode(ALICE, Optional.empty(), token)).contains(AT);
        assertThat(codec.decode(ALICE, Optional.of(IntentState.COMPLETED),
                codec.encode(ALICE, Optional.of(IntentState.COMPLETED), AT))).contains(AT);
    }

    // Covers: 23.2 (the cursor is URL safe and carries no owner: only base64url characters and dots)
    @Test
    void encode_isUrlSafeAndHoldsNoOwner() {
        String token = codec.encode(ALICE, Optional.empty(), AT);

        assertThat(token).matches("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(token).doesNotContain("alice");
    }

    // Covers: T2, T6 (a cursor minted for alice is refused for bob, and one minted for a filter is refused for another)
    @Test
    void decode_anotherCallerOrFilter_isRefused() {
        String token = codec.encode(ALICE, Optional.empty(), AT);

        assertThat(codec.decode(BOB, Optional.empty(), token)).isEmpty();
        assertThat(codec.decode(ALICE, Optional.of(IntentState.COMPLETED), token)).isEmpty();
    }

    // Covers: 23.2 (a forged cursor, with a valid shape but a position the server never issued, is refused)
    @Test
    void decode_forgedPosition_isRefused() {
        String token = codec.encode(ALICE, Optional.empty(), AT);
        String[] parts = token.split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2099-01-01T00:00:00Z\nzzz".getBytes(StandardCharsets.UTF_8));

        assertThat(codec.decode(ALICE, Optional.empty(), parts[0] + "." + forged + "." + parts[2])).isEmpty();
        assertThat(new IntentCursorCodec("another-key-of-enough-length-012345".getBytes(StandardCharsets.UTF_8))
                .decode(ALICE, Optional.empty(), token)).as("a cursor from another key").isEmpty();
    }

    // Covers: 23.2 (garbled input is refused, never parsed into a position)
    @Test
    void decode_garbled_isRefused() {
        String token = codec.encode(ALICE, Optional.empty(), AT);
        String[] parts = token.split("\\.");

        for (String bad : new String[] {"", ".", "..", "v1", "v1..", "v2." + parts[1] + "." + parts[2],
                token + ".x", "v1.!!!." + parts[2], "v1." + parts[1] + ".%%%", parts[1],
                token.substring(0, token.length() - 1),
                "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString("no-newline".getBytes(
                        StandardCharsets.UTF_8)) + "." + parts[2],
                "x".repeat(5000)}) {
            assertThat(codec.decode(ALICE, Optional.empty(), bad)).as(bad).isEmpty();
        }
    }

    // Covers: 23.2 (a well-signed cursor whose position does not parse is refused: the parse runs only after the MAC)
    @Test
    void decode_signedButUnparseablePosition_isRefused() {
        assertThat(codec.decode(ALICE, Optional.empty(), codec.tokenFor(ALICE, Optional.empty(), "not-a-time\nid")))
                .isEmpty();
        assertThat(codec.decode(ALICE, Optional.empty(),
                codec.tokenFor(ALICE, Optional.empty(), "2026-01-01T00:00:00Z\n"))).isEmpty();
    }

    // Covers: 23.2 (the key is long enough to be a secret)
    @Test
    void constructor_shortKey_isRefused() {
        assertThatThrownBy(() -> new IntentCursorCodec(new byte[31])).isInstanceOf(IllegalArgumentException.class);
    }
}
