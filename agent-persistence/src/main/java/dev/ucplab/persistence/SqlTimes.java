package dev.ucplab.persistence;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Times in the database are ISO-8601 UTC text of one fixed width: nine fractional digits and a {@code Z}, for example
 * {@code 2026-01-01T00:00:00.000000000Z} (section 10). {@link Instant#toString()} drops trailing zeros, so its width
 * varies, and a lease is compared as text: {@code ...:00Z} would sort after {@code ...:00.250Z}. With one width the text
 * order is the time order.
 */
public final class SqlTimes {

    /** Length of {@code yyyy-MM-ddTHH:mm:ss.nnnnnnnnnZ}. */
    static final int WIDTH = 30;

    private static final DateTimeFormatter FORMAT = new java.time.format.DateTimeFormatterBuilder()
            .appendInstant(9)
            .toFormatter();

    private SqlTimes() {
    }

    /** @throws IllegalArgumentException when the year does not fit four digits, which would change the width */
    public static String format(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        String text = FORMAT.format(instant);
        if (text.length() != WIDTH) {
            throw new IllegalArgumentException("instant outside the years 0000 to 9999: " + instant);
        }
        return text;
    }

    /** @throws IllegalArgumentException when the text is not in the fixed-width form */
    public static Instant parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() != WIDTH) {
            throw new IllegalArgumentException("not a fixed-width time: " + text);
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not a fixed-width time: " + text, e);
        }
    }
}
