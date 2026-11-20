package dev.ucplab.runtime;

import dev.ucplab.core.money.MoneyFormat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.core.json.CanonicalJson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Walks a tool result and checks every string in it against the fields declared wrapped (6.4, 9.3 L2): merchant and
 * user text is always wrapped, merchant ids are as they are only when they look like ids, and our own fields are ids,
 * codes or states.
 */
final class ResultFields {

    /** Always merchant or user text: wrapped, whatever it holds. */
    static final Set<String> WRAPPED = Set.of("title", "description", "answer", "seller", "name", "label");
    /** Merchant-sent ids and codes: as they are when they look like ids, else wrapped. */
    static final Set<String> WRAPPED_UNLESS_ID = Set.of("item_id", "order_id", "failure_reason");
    /** Our own fields: ids, codes, states and the currency. */
    static final Set<String> OWN = Set.of("merchant_id", "intent_id", "state", "currency", "error", "country");
    /** Fixed texts of ours, each with the only values it may hold (6.2 note: never merchant text). */
    static final Map<String, Set<String>> FIXED = Map.of("note", Set.of(GetPurchaseStatusTool.HANDED_OFF_NOTE),
            "country_name", BuyerCountry.all().stream().map(BuyerCountry::name).collect(Collectors.toSet()));
    /** Containers and numbers. */
    static final Set<String> STRUCTURE = Set.of("items", "quote", "lines", "price_minor", "total_minor", "quantity",
            "unit_price_minor", "in_stock", "options", "above_budget", "count", "lowest_price_minor", "ships_to",
            "other_currency", "budget", "quoted", "amount_minor");
    /**
     * 6.2 amounts as text: each amount in minor units with its text twin in the same object, which must be 24.3's money
     * format of that amount in that object's currency. A line's unit price has none.
     */
    static final Map<String, String> AMOUNT_TEXT = Map.of("price_minor", "price", "total_minor", "total",
            "lowest_price_minor", "lowest_price", "amount_minor", "amount");

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_\\-]{1,100}");

    private ResultFields() {
    }

    static void check(String json) {
        walk(null, CanonicalJson.read(json));
    }

    private static void walk(String key, Object value) {
        switch (value) {
            case Map<?, ?> map -> {
                map.forEach((k, v) -> {
                    assertThat(WRAPPED.contains(k) || WRAPPED_UNLESS_ID.contains(k) || OWN.contains(k)
                            || FIXED.containsKey(k) || STRUCTURE.contains(k) || AMOUNT_TEXT.containsValue(k))
                            .as("a field this test knows: " + k).isTrue();
                    if (!AMOUNT_TEXT.containsValue(k)) {
                        walk((String) k, v);
                    }
                });
                AMOUNT_TEXT.forEach((minor, text) -> {
                    if (map.containsKey(minor)) {
                        assertThat(map.get(text)).as(text + " beside " + minor + " (6.2, 24.3)")
                                .isEqualTo(MoneyFormat.format(((Number) map.get(minor)).longValue(),
                                        (String) map.get("currency")));
                    }
                    assertThat(map.containsKey(text)).as(text + " only beside " + minor).isEqualTo(map.containsKey(minor));
                });
            }
            case List<?> list -> list.forEach(v -> walk(key, v));
            case String s -> checkString(key, s);
            default -> assertThat(STRUCTURE).as("a number or boolean only in a structure field").contains(key);
        }
    }

    private static void checkString(String key, String s) {
        if (FIXED.containsKey(key)) {
            assertThat(FIXED.get(key)).as(key + " is one of our fixed texts").contains(s);
        } else if (WRAPPED.contains(key)) {
            assertWrapped(key, s);
        } else if (WRAPPED_UNLESS_ID.contains(key)) {
            // An id a tool accepts back (Args.ITEM_ID: the lab's, and a Shopify gid, H5, 26.7) passes as it is.
            if (!ID.matcher(s).matches() && !Args.ITEM_ID.matcher(s).matches()) {
                assertWrapped(key, s);
            }
        } else {
            assertThat(s).as(key + " is our own").matches(ID);
        }
    }

    static void assertWrapped(String key, String s) {
        assertThat(s).as(key + " is wrapped").matches("(?s)<untrusted source=\"(merchant|user)\" field=\"" + key
                + "\">.*</untrusted>");
        assertThat(s.indexOf("</untrusted>")).as(key + ": no early close").isEqualTo(s.length() - 12);
        assertThat(s.indexOf("<untrusted", 1)).as(key + ": no inner open").isEqualTo(-1);
    }
}
