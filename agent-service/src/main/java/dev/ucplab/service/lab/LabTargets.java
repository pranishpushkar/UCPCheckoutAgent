package dev.ucplab.service.lab;

import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.service.config.MerchantSettings;
import dev.ucplab.service.config.ServiceProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The lab's reach into each configured merchant (23.3, DESIGN 19): its fault proxy, where the agent reaches the merchant,
 * and the merchant itself, for ground truth and resets. One of each per merchant that has a {@code lab_url}; a merchant
 * without one cannot be controlled. Local profile only. {@link #defaultMerchant()} is the first merchant of the file, which
 * the routes mean when a request names none, so M6's API is unchanged.
 */
@Component
@Profile("local")
public class LabTargets {

    /** The two admin handles of one merchant. */
    public record Target(ProxyAdmin proxy, MerchantLab merchant) {
        public Target {
            Objects.requireNonNull(proxy, "proxy");
            Objects.requireNonNull(merchant, "merchant");
        }
    }

    private final Map<MerchantId, Target> targets;
    private final MerchantId defaultMerchant;

    @Autowired
    public LabTargets(ServiceProperties properties) {
        Map<MerchantId, Target> byId = new LinkedHashMap<>();
        for (MerchantSettings s : properties.merchants()) {
            s.labUrl().ifPresent(lab -> byId.put(s.id(), new Target(new ProxyAdmin(s.url()), new MerchantLab(lab))));
        }
        this.targets = Map.copyOf(byId);
        this.defaultMerchant = properties.merchants().getFirst().id();
    }

    /** For a test or another wiring: these targets, the first being the default. */
    public LabTargets(MerchantId defaultMerchant, Map<MerchantId, Target> targets) {
        this.defaultMerchant = Objects.requireNonNull(defaultMerchant, "defaultMerchant");
        this.targets = Map.copyOf(Objects.requireNonNull(targets, "targets"));
    }

    public MerchantId defaultMerchant() {
        return defaultMerchant;
    }

    public Optional<Target> find(MerchantId id) {
        return Optional.ofNullable(targets.get(Objects.requireNonNull(id, "id")));
    }

    /** The controllable merchants, in no particular order. */
    public List<MerchantId> ids() {
        return List.copyOf(targets.keySet());
    }
}
