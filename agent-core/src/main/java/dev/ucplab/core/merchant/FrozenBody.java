package dev.ucplab.core.merchant;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The {@code complete} request body, built once when the intent becomes APPROVED and re-sent byte for byte (7.4, I7).
 * Holds a payment credential, so it is never logged: {@link #toString()} hides the content.
 */
public record FrozenBody(String json) {

    public FrozenBody {
        Objects.requireNonNull(json, "json");
        if (json.isBlank()) {
            throw new IllegalArgumentException("FrozenBody must not be blank");
        }
    }

    /** The exact bytes to send. */
    public byte[] bytes() {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "FrozenBody[" + bytes().length + " bytes]";
    }
}
