package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.json.CanonicalJson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Walks a tool result and checks every string in it against the fields declared wrapped (6.4, 9.3 L2): merchant and
 * user text is always wrapped, merchant ids are as they are only when they look like ids, and our own fields are ids,
 * codes or states.
 */
final class ResultFields {

    /** Always merchant or user text: wrapped, whatever it holds. */
    static final Set<String> WRAPPED = Set.of("title", "description", "answer");
    /** Merchant-sent ids and codes: as they are when they look like ids, else wrapped. */
    static final Set<String> WRAPPED_UNLESS_ID = Set.of("item_id", "order_id", "failure_reason");
    /** Our own fields: ids, codes, states and the currency. */
    static final Set<String> OWN = Set.of("merchant_id", "intent_id", "state", "currency", "error");
    /** Containers and numbers. */
    static final Set<String> STRUCTURE = Set.of("items", "quote", "lines", "price_minor", "total_minor", "quantity",
            "unit_price_minor", "in_stock");

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_\\-]{1,100}");

    private ResultFields() {
    }

    static void check(String json) {
        walk(null, CanonicalJson.read(json));
    }

    private static void walk(String key, Object value) {
        switch (value) {
            case Map<?, ?> map -> map.forEach((k, v) -> {
                assertThat(WRAPPED.contains(k) || WRAPPED_UNLESS_ID.contains(k) || OWN.contains(k)
                        || STRUCTURE.contains(k)).as("a field this test knows: " + k).isTrue();
                walk((String) k, v);
            });
            case List<?> list -> list.forEach(v -> walk(key, v));
            case String s -> checkString(key, s);
            default -> assertThat(STRUCTURE).as("a number or boolean only in a structure field").contains(key);
        }
    }

    private static void checkString(String key, String s) {
        if (WRAPPED.contains(key)) {
            assertWrapped(key, s);
        } else if (WRAPPED_UNLESS_ID.contains(key)) {
            if (!ID.matcher(s).matches()) {
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
