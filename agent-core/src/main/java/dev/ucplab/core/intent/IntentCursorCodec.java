package dev.ucplab.core.intent;

import dev.ucplab.core.crypto.HmacSha256;
import dev.ucplab.core.identity.UserId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * The opaque {@code cursor} of {@code GET /api/intents} (23.2): {@code v1.<position>.<mac>}, both parts base64url
 * without padding. The position is the last intent's {@code created_at} (ISO-8601, to the nanosecond) and id. The MAC is
 * HMAC-SHA256 over the version, the caller's user id, the state filter and the position, so a cursor is refused when it
 * was altered, forged, minted under another key, for another user or for another filter. The owner's id is bound but
 * not carried. Decoding never throws on client input: anything that does not verify and parse is empty.
 */
public final class IntentCursorCodec {

    /** The shortest key accepted: 256 bits, as for the approval secret. */
    public static final int MIN_KEY_BYTES = 32;

    /** Longer input is refused before any decoding: a real cursor is well under this. */
    private static final int MAX_TOKEN_CHARS = 1024;
    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;

    public IntentCursorCodec(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("a cursor key needs at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    public String encode(UserId owner, Optional<IntentState> state, IntentCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        return tokenFor(owner, state, cursor.createdAt() + "\n" + cursor.id().value());
    }

    /** The position, when {@code token} was issued by this codec for {@code owner} and {@code state}; else empty. */
    public Optional<IntentCursor> decode(UserId owner, Optional<IntentState> state, String token) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(token, "token");
        if (token.length() > MAX_TOKEN_CHARS) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals(VERSION)) {
            return Optional.empty();
        }
        byte[] position;
        byte[] mac;
        try {
            position = DECODER.decode(parts[1]);
            mac = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(mac, mac(owner, state, position))) {
            return Optional.empty();
        }
        return parse(new String(position, StandardCharsets.UTF_8));
    }

    /** Signs a position text as {@link #encode} does; package-private so a test can sign a position that won't parse. */
    String tokenFor(UserId owner, Optional<IntentState> state, String position) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(state, "state");
        byte[] bytes = position.getBytes(StandardCharsets.UTF_8);
        return VERSION + "." + ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(mac(owner, state, bytes));
    }

    private byte[] mac(UserId owner, Optional<IntentState> state, byte[] position) {
        byte[] head = ("intent-cursor/" + VERSION + "\n" + owner.value().length() + ":" + owner.value() + "\n"
                + state.map(Enum::name).orElse("*") + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[head.length + position.length];
        System.arraycopy(head, 0, message, 0, head.length);
        System.arraycopy(position, 0, message, head.length, position.length);
        return HmacSha256.compute(key, message);
    }

    private static Optional<IntentCursor> parse(String position) {
        int newline = position.indexOf('\n');
        if (newline < 0 || newline == position.length() - 1) {
            return Optional.empty();
        }
        String id = position.substring(newline + 1);
        if (id.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new IntentCursor(Instant.parse(position.substring(0, newline)), new IntentId(id)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
