package dev.ucplab.core.merchant;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A frozen request body, stored in {@code merchant_attempt.request_body} and re-sent byte for byte under the same key
 * (7.3, 7.4, I7). The {@code complete} body is built once per approval and holds a payment credential; create and
 * update bodies hold the buyer email. Neither is ever logged: {@link #toString()} hides the content.
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
