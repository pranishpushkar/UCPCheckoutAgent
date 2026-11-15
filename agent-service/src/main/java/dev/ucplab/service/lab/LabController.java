package dev.ucplab.service.lab;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.service.web.ApiException;
import dev.ucplab.service.web.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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

    public LabController(LabControls controls) {
        this.controls = Objects.requireNonNull(controls, "controls");
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
