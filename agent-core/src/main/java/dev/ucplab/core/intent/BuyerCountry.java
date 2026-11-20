package dev.ucplab.core.intent;

import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The run's buyer country (23.1, 26.7, Appendix B): an ISO 3166-1 alpha-2 code the JDK knows, with one ISO 4217
 * currency. It comes from the user's Deliver to choice on {@code POST /api/runs}, or the operator's
 * {@code BUYER_COUNTRY}; never from the buyer's IP, a header, the message or model output. Its currency and English name
 * are the JDK's tables, not FX.
 */
public record BuyerCountry(String code) {

    private static final Pattern CODE = Pattern.compile("[A-Z]{2}");
    private static final Set<String> ISO = Set.of(Locale.getISOCountries());

    /** The default when neither the user nor the operator names one (23.1). */
    public static final BuyerCountry US = new BuyerCountry("US");

    private static final List<BuyerCountry> ALL = Stream.of(Locale.getISOCountries())
            .filter(c -> currencyOf(c).isPresent()).map(BuyerCountry::new)
            .sorted(Comparator.comparing(BuyerCountry::name)).toList();

    public BuyerCountry {
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches() || !ISO.contains(code) || currencyOf(code).isEmpty()) {
            throw new IllegalArgumentException(
                    "a buyer country is two upper-case letters naming an ISO 3166-1 country with one currency");
        }
    }

    /** The country's ISO 4217 currency, from the JDK's table: INR for IN. */
    public String currency() {
        return currencyOf(code).orElseThrow();
    }

    /** The country's English name, from the JDK's table: India for IN. Our own text, never merchant text. */
    public String name() {
        return Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
    }

    /** Every country with one currency, sorted by English name (23.1 {@code GET /api/countries}). */
    public static List<BuyerCountry> all() {
        return ALL;
    }

    /** The country's currency, or empty when it has none (Antarctica) or the table names no real one. */
    private static Optional<String> currencyOf(String code) {
        try {
            return Optional.ofNullable(Currency.getInstance(Locale.of("", code))).map(Currency::getCurrencyCode)
                    .filter(c -> !c.equals("XXX"));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
