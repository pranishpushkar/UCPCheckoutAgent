package dev.ucplab.eval.report;

import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Model prices for the live report's cost column (13.3, 13.4, m5-plan section 9): read at run time from a JSON file,
 * never hard-coded, and named in the report with the model and the date the prices were taken.
 *
 * <pre>
 * {"model": "...", "date": "2026-11-13", "currency": "USD",
 *  "inputPerMillionTokens": "0.30", "outputPerMillionTokens": "2.50", "source": "https://..."}
 * </pre>
 *
 * Prices may be JSON numbers or decimal strings; unknown fields are refused.
 */
public record PriceTable(String model, LocalDate date, String currency, BigDecimal inputPerMillionTokens,
                         BigDecimal outputPerMillionTokens, String source) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final Set<String> FIELDS = Set.of("model", "date", "currency", "inputPerMillionTokens",
            "outputPerMillionTokens", "source");

    public PriceTable {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(inputPerMillionTokens, "inputPerMillionTokens");
        Objects.requireNonNull(outputPerMillionTokens, "outputPerMillionTokens");
        Objects.requireNonNull(source, "source");
        if (model.isBlank() || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("a price table needs a model and an ISO 4217 currency");
        }
        if (inputPerMillionTokens.signum() < 0 || outputPerMillionTokens.signum() < 0) {
            throw new IllegalArgumentException("prices must not be negative");
        }
    }

    /** @throws IllegalArgumentException if the file is not a valid price table */
    public static PriceTable read(Path file) throws IOException {
        Map<String, Object> m = LenientJson.readObject(Files.readString(file, StandardCharsets.UTF_8));
        for (String key : m.keySet()) {
            if (!FIELDS.contains(key)) {
                throw new IllegalArgumentException("price table: unknown field \"" + key + "\"");
            }
        }
        return new PriceTable(text(m, "model"), LocalDate.parse(text(m, "date")), text(m, "currency"),
                price(m, "inputPerMillionTokens"), price(m, "outputPerMillionTokens"), text(m, "source"));
    }

    /** The cost of {@code input} and {@code output} tokens, in {@link #currency}. */
    public BigDecimal cost(long input, long output) {
        if (input < 0 || output < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        return inputPerMillionTokens.multiply(BigDecimal.valueOf(input))
                .add(outputPerMillionTokens.multiply(BigDecimal.valueOf(output)))
                .divide(MILLION, MathContext.DECIMAL64).stripTrailingZeros();
    }

    private static String text(Map<String, Object> m, String key) {
        if (!(m.get(key) instanceof String s)) {
            throw new IllegalArgumentException("price table: \"" + key + "\" must be a string");
        }
        return s;
    }

    private static BigDecimal price(Map<String, Object> m, String key) {
        Object v = m.get(key);
        try {
            return switch (v) {
                case String s -> new BigDecimal(s);
                case Long l -> BigDecimal.valueOf(l);
                case Integer i -> BigDecimal.valueOf(i);
                case BigDecimal d -> d;
                case null, default -> throw new IllegalArgumentException("price table: \"" + key
                        + "\" must be a number");
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("price table: \"" + key + "\" must be a number");
        }
    }
}
