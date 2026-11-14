package dev.ucplab.service.config;

import dev.ucplab.core.policy.PolicyConfig;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads {@code POLICY_FILE} ({@code policy.yaml}, 9.1) into a {@link PolicyConfig}. The keys are the record's
 * components in snake case; a key left out keeps its 9.1 default. An unknown key, a value of the wrong type or out of
 * range, or a file that cannot be read refuses startup with a fixed message that names {@code POLICY_FILE} and never
 * echoes the file's content. YAML is parsed with the safe constructor only, so no type tag can build an object.
 */
final class PolicyFile {

    static final String UNREADABLE = "POLICY_FILE cannot be read";
    static final String NOT_A_MAP = "POLICY_FILE must be a map of settings";
    static final String UNKNOWN_KEY = "POLICY_FILE has an unknown setting";
    static final String WRONG_TYPE = "POLICY_FILE has a setting of the wrong type";
    static final String OUT_OF_RANGE = "POLICY_FILE has a setting out of range";

    private static final Set<String> KEYS = Set.of("per_purchase_cap_minor", "daily_cap_minor",
            "max_quantity_per_line", "max_lines", "max_proposals_per_hour", "approval_required", "max_requotes");

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
                    longValue(map, "per_purchase_cap_minor", d.perPurchaseCapMinor()),
                    longValue(map, "daily_cap_minor", d.dailyCapMinor()),
                    intValue(map, "max_quantity_per_line", d.maxQuantityPerLine()),
                    intValue(map, "max_lines", d.maxLines()),
                    intValue(map, "max_proposals_per_hour", d.maxProposalsPerHour()),
                    booleanValue(map, "approval_required", d.approvalRequired()),
                    intValue(map, "max_requotes", d.maxRequotes()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(OUT_OF_RANGE);
        }
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
