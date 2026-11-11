package dev.ucplab.core.json;

import java.util.Map;
import java.util.Objects;

/**
 * Reads JSON from a merchant response (8.4). Unlike {@link CanonicalJson#read}, it allows {@code null}, decimals,
 * exponents and integers beyond the range of a {@code long}, because a response may carry them in fields the client
 * ignores. Values: {@code Map} (sorted keys), {@code List}, {@code String}, {@code Boolean}, {@code Long} for an
 * integer that fits, {@code BigDecimal} for any other number, and Java {@code null}. Duplicate keys, trailing text and
 * malformed syntax are still refused. It reads only: the canonical writer and the hashes (7.4, 9.2) stay strict.
 */
public final class LenientJson {

    private LenientJson() {
    }

    /**
     * Parses a document whose top level must be an object.
     *
     * @throws IllegalArgumentException on malformed input or a non-object top level
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String json) {
        Objects.requireNonNull(json, "json");
        CanonicalJson.Reader reader = new CanonicalJson.Reader(json, true);
        Object value = reader.value();
        reader.skipWhitespace();
        reader.requireEnd();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) map;
    }
}
