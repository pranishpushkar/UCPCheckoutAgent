package dev.ucplab.ucp;

import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.ContinueUrl;
import dev.ucplab.core.merchant.LinkOffer;
import dev.ucplab.core.merchant.Money;
import dev.ucplab.core.merchant.Seller;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the Global Catalog's answers ({@code structuredContent}, 2026-04-08; 26.7) into core types. Pure.
 *
 * <p>One item per variant: a variant needs an {@code id} and a {@code price} with an integer {@code amount} in minor
 * units in {@value #CURRENCY}; any other variant is left out, and a product left with none is left out with it. A
 * variant's title defaults to its product's. {@code availability.available} is read as it is, and {@code seller.name}
 * as the variant's seller. Nothing else of the offer is kept for search or product: no {@code checkout_url}, no
 * {@code url}, no seller domain, url or links (I6). Only {@link #offer}, the orchestrator's read for a link intent's
 * quote, keeps the seller's domain, the host of its url and the variant's {@code checkout_url} (26.7). Titles, descriptions and seller names are the seller's text: markup removed
 * ({@link MerchantMarkup}), control characters replaced, cut to a fixed length (6.4).
 */
final class GlobalCatalogParser {

    /** The only currency a link intent is priced in (26.7). */
    static final String CURRENCY = "USD";
    private static final int MAX_TEXT = 200;
    private static final int MAX_DESCRIPTION = 2000;

    private GlobalCatalogParser() {
    }

    /** A search answer: {@code products} and {@code pagination.has_next_page}. */
    static CatalogParser.Parsed<CatalogPage> search(byte[] body) {
        return read(body, root -> {
            if (!(root.get("products") instanceof List<?> list)) {
                return new CatalogParser.Parsed.Invalid<>("products missing");
            }
            List<CatalogProduct> products = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> p) {
                    product(p).ifPresent(products::add);
                }
            }
            boolean more = root.get("pagination") instanceof Map<?, ?> p && Boolean.TRUE.equals(p.get("has_next_page"));
            return new CatalogParser.Parsed.Found<>(new CatalogPage(products, more));
        });
    }

    /** A {@code get_product} answer: {@code product}, with at least one variant this parser keeps. */
    static CatalogParser.Parsed<CatalogProduct> product(byte[] body) {
        return read(body, root -> root.get("product") instanceof Map<?, ?> p
                ? product(p).<CatalogParser.Parsed<CatalogProduct>>map(CatalogParser.Parsed.Found::new)
                        .orElseGet(() -> new CatalogParser.Parsed.Invalid<>("no variant in " + CURRENCY))
                : new CatalogParser.Parsed.Invalid<>("product missing"));
    }

    /**
     * A {@code get_product} answer as the offer of {@code itemId} (7.2 quote_ready (link)): the variant with that id, its
     * seller's name, domain and site (the host of {@code seller.url}, lower-cased), and its {@code checkout_url} exactly
     * as sent. A variant the answer does not hold is the catalog's refusal ({@code not_found}); a variant without a USD
     * price or without a seller name and domain is unreadable.
     */
    static CatalogParser.Parsed<LinkOffer> offer(byte[] body, String itemId) {
        return read(body, root -> {
            if (!(root.get("product") instanceof Map<?, ?> p) || !(p.get("variants") instanceof List<?> variants)) {
                return new CatalogParser.Parsed.Invalid<>("product missing");
            }
            String productTitle = string(p.get("title")).map(t -> clean(MerchantMarkup.strip(t), MAX_TEXT)).orElse("");
            for (Object o : variants) {
                if (o instanceof Map<?, ?> v && itemId.equals(v.get("id"))) {
                    return offer(v, productTitle);
                }
            }
            return new CatalogParser.Parsed.Error<>(NOT_FOUND, "the variant is not in the answer");
        });
    }

    /** The code of {@link #offer}'s refusal when the answer does not hold the variant. */
    static final String NOT_FOUND = "not_found";

    private static CatalogParser.Parsed<LinkOffer> offer(Map<?, ?> v, String productTitle) {
        Optional<CatalogVariant> variant = variant(v, productTitle);
        if (variant.isEmpty()) {
            return new CatalogParser.Parsed.Invalid<>("no price in " + CURRENCY);
        }
        if (!(v.get("seller") instanceof Map<?, ?> s)) {
            return new CatalogParser.Parsed.Invalid<>("no seller");
        }
        Optional<String> name = variant.get().seller();
        Optional<String> domain = string(s.get("domain")).map(d -> d.trim().toLowerCase(Locale.ROOT));
        if (name.isEmpty() || domain.isEmpty()) {
            return new CatalogParser.Parsed.Invalid<>("no seller name or domain");
        }
        Optional<String> site = string(s.get("url")).flatMap(ContinueUrl::parse)
                .flatMap(u -> Optional.ofNullable(u.value().getHost())).map(h -> h.toLowerCase(Locale.ROOT));
        Optional<ContinueUrl> link = string(v.get("checkout_url")).flatMap(ContinueUrl::parse);
        CatalogVariant kept = variant.get();
        return new CatalogParser.Parsed.Found<>(new LinkOffer(kept.itemId(), kept.title(), kept.price(),
                kept.availability().available(), new Seller(name.get(), domain.get(), site), link));
    }

    private interface Reader<T> {
        CatalogParser.Parsed<T> read(Map<String, Object> root);
    }

    private static <T> CatalogParser.Parsed<T> read(byte[] body, Reader<T> reader) {
        Map<String, Object> root;
        try {
            root = LenientJson.readObject(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return new CatalogParser.Parsed.Invalid<>("unparseable body");
        }
        if (root.get("ucp") instanceof Map<?, ?> ucp && "error".equals(ucp.get("status"))) {
            if (root.get("messages") instanceof List<?> l && !l.isEmpty() && l.getFirst() instanceof Map<?, ?> m) {
                String code = m.get("code") instanceof String s && !s.isBlank() ? clean(s, 64) : "error";
                String content = m.get("content") instanceof String s ? clean(s, MAX_TEXT) : "";
                return new CatalogParser.Parsed.Error<>(code, content);
            }
            return new CatalogParser.Parsed.Invalid<>("error envelope without messages");
        }
        return reader.read(root);
    }

    /** The product with its kept variants, or empty when it has no id, no title or no kept variant. */
    static Optional<CatalogProduct> product(Map<?, ?> p) {
        Optional<String> id = string(p.get("id"));
        Optional<String> title = string(p.get("title")).map(t -> clean(MerchantMarkup.strip(t), MAX_TEXT));
        if (id.isEmpty() || title.isEmpty() || !(p.get("variants") instanceof List<?> variants)) {
            return Optional.empty();
        }
        List<CatalogVariant> kept = new ArrayList<>();
        for (Object o : variants) {
            if (o instanceof Map<?, ?> v) {
                variant(v, title.get()).ifPresent(kept::add);
            }
        }
        return kept.isEmpty() ? Optional.empty()
                : Optional.of(new CatalogProduct(id.get(), title.get(), description(p.get("description")), kept));
    }

    /** The variant, or empty when it has no id or no integer price in {@value #CURRENCY}. */
    static Optional<CatalogVariant> variant(Map<?, ?> v, String productTitle) {
        Optional<String> id = string(v.get("id"));
        if (id.isEmpty() || !(v.get("price") instanceof Map<?, ?> price) || !(price.get("amount") instanceof Long amount)
                || amount < 0 || !CURRENCY.equals(price.get("currency"))) {
            return Optional.empty();
        }
        String title = string(v.get("title")).map(t -> clean(MerchantMarkup.strip(t), MAX_TEXT)).filter(t -> !t.isBlank())
                .orElse(productTitle);
        boolean available = v.get("availability") instanceof Map<?, ?> a && Boolean.TRUE.equals(a.get("available"));
        Optional<String> seller = v.get("seller") instanceof Map<?, ?> s
                ? string(s.get("name")).map(n -> clean(MerchantMarkup.strip(n), MAX_TEXT)).filter(n -> !n.isBlank())
                : Optional.empty();
        return Optional.of(new CatalogVariant(id.get(), title, new Money(amount, CURRENCY),
                new Availability(available, Optional.empty()), seller));
    }

    private static Optional<String> description(Object value) {
        if (!(value instanceof Map<?, ?> d) || !(d.get("plain") instanceof String plain)) {
            return Optional.empty();
        }
        return Optional.of(clean(MerchantMarkup.strip(plain), MAX_DESCRIPTION)).filter(c -> !c.isBlank());
    }

    private static Optional<String> string(Object value) {
        return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    static String clean(String s, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char c = s.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString().trim();
    }
}
