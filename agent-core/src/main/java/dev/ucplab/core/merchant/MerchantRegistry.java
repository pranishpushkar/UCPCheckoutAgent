package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Optional;

/** The merchants this agent may use (section 19, Appendix B). */
public interface MerchantRegistry {

    List<MerchantEntry> active();

    Optional<MerchantEntry> find(MerchantId id);

    /** Cached up to 5 min (section 20), refreshed on a 4xx or schema mismatch. */
    MerchantProfile profile(MerchantId id);
}
