package dev.ucplab.ucp;

import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.Money;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads catalog responses (2026-08-25 {@code catalog_search}, {@code catalog_lookup}) into core types. Pure.
 *
 * <p>A product is invalid, and with it the whole body, if a field this client needs is missing or has the wrong type:
 * {@code id}, {@code title}, and at least one variant with {@code id}, {@code title} and {@code price} (an integer
 * {@code amount} in minor units and a three-letter {@code currency}). {@code availability} is optional; a variant
 * without it is not available (see {@link Availability}). Unknown fields, including {@code null}s and decimals in fields
 * not used, are ignored. Titles and statuses are merchant text: control characters are replaced and they are cut to a
 * fixed length, because they reach the model and the logs.
 */
final class CatalogParser {

    private static final int MAX_TEXT = 200;

    private CatalogParser() {
    }

    /** The result of reading a 200 body. {@code Invalid.reason} names a field, never a value. */
    sealed interface Parsed<T> {

        record Found<T>(T value) implements Parsed<T> {
            public Found {
                Objects.requireNonNull(value, "value");
            }
        }

        /** {@code ucp.status} error: the merchant answered, but with a business error. Text is cleaned and cut. */
        record Error<T>(String code, String content) implements Parsed<T> {
            public Error {
                Objects.requireNonNull(code, "code");
                Objects.requireNonNull(content, "content");
            }
        }

        record Invalid<T>(String reason) implements Parsed<T> {
            public Invalid {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /** A search response: {@code products} and {@code pagination.has_next_page}. */
    static Parsed<CatalogPage> parseSearch(byte[] body) {
        return read(body, root -> {
            boolean more = root.get("pagination") instanceof Map<?, ?> p && Boolean.TRUE.equals(p.get("has_next_page"));
            return new CatalogPage(products(root.get("products")), more);
        });
    }

    /** A lookup response: {@code products}; ids the merchant did not find are simply absent. */
    static Parsed<List<CatalogProduct>> parseLookup(byte[] body) {
        return read(body, root -> products(root.get("products")));
    }

    /** A product response: {@code product}, or an error envelope such as {@code not_found}. */
    static Parsed<CatalogProduct> parseProduct(byte[] body) {
        return read(body, root -> {
            if (!(root.get("product") instanceof Map<?, ?> product)) {
                throw new Bad("product missing");
            }
            return product(product);
        });
    }

    private interface Reader<T> {
        T read(Map<String, Object> root);
    }

    private static <T> Parsed<T> read(byte[] body, Reader<T> reader) {
        Objects.requireNonNull(body, "body");
        try {
            Map<String, Object> root = LenientJson.readObject(decode(body));
            if (root.get("ucp") instanceof Map<?, ?> ucp && "error".equals(ucp.get("status"))) {
                return error(root);
            }
            return new Parsed.Found<>(reader.read(root));
        } catch (Bad | IllegalArgumentException | CharacterCodingException e) {
            return new Parsed.Invalid<>(e instanceof Bad ? e.getMessage() : "unparseable body");
        }
    }

    private static <T> Parsed<T> error(Map<String, Object> root) {
        if (root.get("messages") instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
            String code = m.get("code") instanceof String s && !s.isBlank() ? clean(s, 64) : "error";
            String content = m.get("content") instanceof String s ? clean(s, MAX_TEXT) : "";
            return new Parsed.Error<>(code, content);
        }
        throw new Bad("error envelope without messages");
    }

    private static List<CatalogProduct> products(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new Bad("products missing");
        }
        List<CatalogProduct> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> p)) {
                throw new Bad("product shape");
            }
            out.add(product(p));
        }
        return out;
    }

    private static CatalogProduct product(Map<?, ?> p) {
        if (!(p.get("variants") instanceof List<?> variants) || variants.isEmpty()) {
            throw new Bad("variants missing");
        }
        List<CatalogVariant> out = new ArrayList<>();
        for (Object o : variants) {
            if (!(o instanceof Map<?, ?> v)) {
                throw new Bad("variant shape");
            }
            out.add(variant(v));
        }
        return new CatalogProduct(text(p, "id"), cleanText(p, "title"), out);
    }

    private static CatalogVariant variant(Map<?, ?> v) {
        if (!(v.get("price") instanceof Map<?, ?> price)) {
            throw new Bad("price missing");
        }
        if (!(price.get("amount") instanceof Long amount)) {
            throw new Bad("price.amount not an integer");
        }
        Money money;
        try {
            money = new Money(amount, text(price, "currency"));
        } catch (IllegalArgumentException e) {
            throw new Bad("price invalid");
        }
        return new CatalogVariant(text(v, "id"), cleanText(v, "title"), money, availability(v.get("availability")));
    }

    private static Availability availability(Object value) {
        if (value == null) {
            return Availability.unstated();
        }
        if (!(value instanceof Map<?, ?> a)) {
            throw new Bad("availability shape");
        }
        boolean available = a.get("available") instanceof Boolean b && b;
        Optional<String> status = a.get("status") instanceof String s && !s.isBlank()
                ? Optional.of(clean(s, 64)) : Optional.empty();
        return new Availability(available, status);
    }

    private static String text(Map<?, ?> map, String key) {
        if (map.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new Bad(key + " missing");
    }

    private static String cleanText(Map<?, ?> map, String key) {
        return clean(text(map, key), MAX_TEXT);
    }

    private static String decode(byte[] body) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString();
    }

    private static String clean(String s, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char c = s.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString();
    }

    /** An invalid field; carries only the field name. No stack trace: it is control flow inside this class. */
    private static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Bad(String reason) {
            super(reason, null, false, false);
        }
    }
}
