package dev.ucplab.service.lab;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.service.config.ServiceProperties;
import dev.ucplab.service.web.ApiException;
import dev.ucplab.service.web.ErrorCode;
import dev.ucplab.service.web.LiveStore;
import dev.ucplab.ucp.registry.ConfiguredMerchantRegistry;
import dev.ucplab.ucp.registry.MerchantSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lab controls (23.3 [CONTRACT]). The bean exists only under {@code local}, so in any other profile these routes
 * are not mapped and answer 404 with or without a session (the session check is an interceptor, which runs after
 * handler mapping). Messages are fixed; a rule set the proxy refuses is 422 and its detail is not passed on.
 */
@RestController
@Profile("local")
public class LabController {

    static final String BAD_FAULTS = "give a preset (drop_next_complete, change_price, merchant_503_20s, merchant_down) "
            + "or rules";
    static final String BAD_RULES = "the proxy refused the rules";
    static final String UNKNOWN_MERCHANT = "unknown merchant_id";

    private final LabControls controls;
    private final ServiceProperties properties;
    private final ConfiguredMerchantRegistry registry;

    public LabController(LabControls controls, ServiceProperties properties, ConfiguredMerchantRegistry registry) {
        this.controls = Objects.requireNonNull(controls, "controls");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * The configured merchants, for the console's selector, counters, header badge and Developer tools (DESIGN 23.3,
     * 24.3): each one's id, name, status, completion, whether the lab controls it and whether it is a live store, with its shop_domain when it is, and
     * the fixed reason while it is SUSPENDED. Never a URL, a lab address or a token reference.
     */
    @GetMapping("/api/lab/merchants")
    Map<String, Object> merchants(AuthContext auth) {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (MerchantSettings m : properties.merchants()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("merchant_id", m.id().value());
            entry.put("name", m.name());
            entry.put("status", registry.find(m.id()).map(e -> e.status().name()).orElse("SUSPENDED"));
            // 23.3, 24.3: for the header badge and the Developer tools before an intent exists.
            entry.put("completion", m.completion().name().toLowerCase(java.util.Locale.ROOT));
            // 23.3, 24.3: whether the lab controls it (its lab_url), so has faults and orders; the console polls only these.
            entry.put("lab", controls.controls(m.id()));
            Optional<String> live = LiveStore.domain(m.owned(), m.shopDomain());
            entry.put("live", live.isPresent());
            live.ifPresent(d -> entry.put("shop_domain", d));
            registry.reason(m.id()).ifPresent(reason -> entry.put("reason", reason));
            list.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchants", list);
        return out;
    }

    @PutMapping("/api/lab/faults")
    Map<String, Object> putFaults(AuthContext auth, @RequestBody Map<String, Object> body) {
        try {
            java.util.List<String> ids;
            MerchantId merchant = merchant(body.get("merchant_id"));
            if (body.get("preset") instanceof String name) {
                LabControls.Preset preset = LabControls.Preset.parse(name)
                        .orElseThrow(() -> ApiException.of(ErrorCode.VALIDATION_FAILED, BAD_FAULTS));
                ids = controls.preset(auth, merchant, preset);
            } else if (body.get("rules") instanceof Map<?, ?> rules) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ruleSet = (Map<String, Object>) rules;
                ids = controls.replaceRules(merchant, ruleSet);
            } else {
                throw ApiException.of(ErrorCode.VALIDATION_FAILED, BAD_FAULTS);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rule_ids", ids);
            return out;
        } catch (ProxyAdmin.RulesRejected e) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, BAD_RULES);
        } catch (LabControls.Refused e) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    @DeleteMapping("/api/lab/faults")
    ResponseEntity<Void> deleteFaults(AuthContext auth,
                                      @RequestParam(name = "merchant_id", required = false) String merchantId) {
        try {
            controls.clearRules(merchant(merchantId));
        } catch (LabControls.Refused e) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/lab/orders")
    Map<String, Object> orders(AuthContext auth, @RequestParam(name = "merchant_id", required = false) String merchantId) {
        if (merchantId == null || !controls.controls(new MerchantId(merchantId))) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, UNKNOWN_MERCHANT);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchant_id", merchantId);
        out.put("orders", controls.orders(new MerchantId(merchantId)));
        return out;
    }

    /** The merchant a request names, or the default one (the first of the file) when it names none. */
    private MerchantId merchant(Object value) {
        if (value == null) {
            return controls.defaultMerchant();
        }
        if (!(value instanceof String id) || id.isBlank() || !controls.controls(new MerchantId(id))) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, UNKNOWN_MERCHANT);
        }
        return new MerchantId(id);
    }

    @PostMapping("/api/lab/reset")
    ResponseEntity<Void> reset(AuthContext auth) {
        controls.reset();
        return ResponseEntity.noContent().build();
    }
}
