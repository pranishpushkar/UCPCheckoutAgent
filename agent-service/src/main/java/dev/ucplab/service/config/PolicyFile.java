package dev.ucplab.service.config;

import dev.ucplab.core.policy.CurrencyCaps;
import dev.ucplab.core.policy.PolicyConfig;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads {@code POLICY_FILE} ({@code policy.yaml}, 9.1) into a {@link PolicyConfig}. The keys are the record's
 * components in snake case; a key left out keeps its 9.1 default. P2 and P3 are {@code caps}, per currency:
 * {@code caps: {INR: {per_purchase_minor: ..., daily_minor: ...}}}; the older flat keys {@code per_purchase_cap_minor}
 * and {@code daily_cap_minor} mean the USD caps, and a file may not use both forms. An unknown key, a value of the wrong type or out of
 * range, or a file that cannot be read refuses startup with a fixed message that names {@code POLICY_FILE} and never
 * echoes the file's content. YAML is parsed with the safe constructor only, so no type tag can build an object.
 */
final class PolicyFile {

    static final String UNREADABLE = "POLICY_FILE cannot be read";
    static final String NOT_A_MAP = "POLICY_FILE must be a map of settings";
    static final String UNKNOWN_KEY = "POLICY_FILE has an unknown setting";
    static final String WRONG_TYPE = "POLICY_FILE has a setting of the wrong type";
    static final String OUT_OF_RANGE = "POLICY_FILE has a setting out of range";
    static final String CAPS_TWICE = "POLICY_FILE sets caps both per currency and with the flat USD keys";
    static final String CAPS_INCOMPLETE = "POLICY_FILE has a currency without both per_purchase_minor and daily_minor";

    private static final Set<String> KEYS = Set.of("caps", "per_purchase_cap_minor", "daily_cap_minor",
            "max_quantity_per_line", "max_lines", "max_proposals_per_hour", "approval_required", "max_requotes");
    private static final Set<String> CAP_KEYS = Set.of("per_purchase_minor", "daily_minor");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private PolicyFile() {
    }

    static PolicyConfig load(Path file) {
        Objects.requireNonNull(file, "file");
        Object root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (IOException | YAMLException e) {
            throw new IllegalStateException(UNREADABLE);
        }
        if (root == null) {
            return PolicyConfig.defaults();
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalStateException(NOT_A_MAP);
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !KEYS.contains(name)) {
                throw new IllegalStateException(UNKNOWN_KEY);
            }
        }
        PolicyConfig d = PolicyConfig.defaults();
        try {
            return new PolicyConfig(
                    caps(map, d),
                    intValue(map, "max_quantity_per_line", d.maxQuantityPerLine()),
                    intValue(map, "max_lines", d.maxLines()),
                    intValue(map, "max_proposals_per_hour", d.maxProposalsPerHour()),
                    booleanValue(map, "approval_required", d.approvalRequired()),
                    intValue(map, "max_requotes", d.maxRequotes()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(OUT_OF_RANGE);
        }
    }

    /** 9.1: {@code caps} per currency, or the flat keys as USD's, or the defaults; never both forms. */
    private static Map<String, CurrencyCaps> caps(Map<?, ?> map, PolicyConfig defaults) {
        boolean flat = map.containsKey("per_purchase_cap_minor") || map.containsKey("daily_cap_minor");
        if (!map.containsKey("caps")) {
            if (!flat) {
                return defaults.caps();
            }
            CurrencyCaps usd = defaults.capsFor(PolicyConfig.USD).orElseThrow();
            return Map.of(PolicyConfig.USD, new CurrencyCaps(
                    longValue(map, "per_purchase_cap_minor", usd.perPurchaseMinor()),
                    longValue(map, "daily_cap_minor", usd.dailyMinor())));
        }
        if (flat) {
            throw new IllegalStateException(CAPS_TWICE);
        }
        if (!(map.get("caps") instanceof Map<?, ?> perCurrency)) {
            throw new IllegalStateException(WRONG_TYPE);
        }
        Map<String, CurrencyCaps> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : perCurrency.entrySet()) {
            if (!(e.getKey() instanceof String currency) || !CURRENCY.matcher(currency).matches()) {
                throw new IllegalStateException(OUT_OF_RANGE);
            }
            if (!(e.getValue() instanceof Map<?, ?> amounts)) {
                throw new IllegalStateException(WRONG_TYPE);
            }
            for (Object key : amounts.keySet()) {
                if (!CAP_KEYS.contains(key)) {
                    throw new IllegalStateException(UNKNOWN_KEY);
                }
            }
            if (!amounts.keySet().containsAll(CAP_KEYS)) {
                throw new IllegalStateException(CAPS_INCOMPLETE);
            }
            out.put(currency, new CurrencyCaps(longValue(amounts, "per_purchase_minor", 0),
                    longValue(amounts, "daily_minor", 0)));
        }
        return out;
    }

    private static long longValue(Map<?, ?> map, String key, long fallback) {
        Object v = map.get(key);
        if (v == null && !map.containsKey(key)) {
            return fallback;
        }
        if (v instanceof Integer || v instanceof Long) {
            return ((Number) v).longValue();
        }
        throw new IllegalStateException(WRONG_TYPE);
    }

    private static int intValue(Map<?, ?> map, String key, int fallback) {
        Object v = map.get(key);
        if (v == null && !map.containsKey(key)) {
            return fallback;
        }
        if (v instanceof Integer i) {
            return i;
        }
        throw new IllegalStateException(WRONG_TYPE);
    }

    private static boolean booleanValue(Map<?, ?> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v == null && !map.containsKey(key)) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException(WRONG_TYPE);
    }
}
