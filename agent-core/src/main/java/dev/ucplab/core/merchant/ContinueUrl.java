package dev.ucplab.core.merchant;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Optional;

/**
 * A merchant's continue_url (Appendix B, 7.2 hand_off). It carries an access key ({@code ?key=}), so {@link #toString()}
 * shows the scheme and host only (I8). Whether it may be used is I12's check, made by the orchestrator; this type only
 * holds it.
 */
public record ContinueUrl(URI value) {

    /** The fixed reason for a continue_url that is not a URI. It never holds the input (I8). */
    public static final String INVALID = "continue_url_invalid";

    public ContinueUrl {
        Objects.requireNonNull(value, "value");
    }

    /**
     * The URL, or empty when {@code raw} is not a URI; callers report {@link #INVALID}. {@link URISyntaxException}'s
     * message holds its input, so it is dropped here and never surfaced.
     */
    public static Optional<ContinueUrl> parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ContinueUrl(new URI(raw)));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        String scheme = value.getScheme();
        String host = value.getHost();
        return scheme == null || host == null ? "[redacted]" : scheme + "://" + host;
    }
}
