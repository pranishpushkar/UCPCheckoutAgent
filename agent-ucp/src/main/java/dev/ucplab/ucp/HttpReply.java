package dev.ucplab.ucp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A complete HTTP response: status, headers (lower-case names) and body bytes. {@link #toString()} omits the body. */
public record HttpReply(int status, Map<String, List<String>> headers, byte[] body) {

    public HttpReply {
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        Objects.requireNonNull(body, "body");
    }

    /** The first value of a header, case-insensitive. */
    public Optional<String> header(String name) {
        Objects.requireNonNull(name, "name");
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    @Override
    public String toString() {
        return "HttpReply[" + status + ", " + body.length + " bytes]";
    }
}
