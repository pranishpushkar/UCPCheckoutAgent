package dev.ucplab.core.intent;

import dev.ucplab.core.merchant.MerchantId;
import java.util.Map;
import java.util.Set;

/**
 * System read beside {@link RecoveryScan} (DESIGN 19, 18.1): the merchants of every intent still in flight, across
 * owners, with the merchant fingerprints those intents stored (10). An intent is in flight when its state is not
 * terminal or it still owes a cancel ({@code cancel_pending}). Startup uses it to refuse a merchants file that drops a
 * merchant those intents need, or changes the entry they started under (I12). It takes no lease, changes nothing and
 * caches nothing (section 20); like {@code RecoveryScan}, it is the only kind of read that crosses owners.
 */
public interface InFlightMerchants {

    /** By merchant, the distinct {@code merchant_fingerprint}s of its intents in flight, whoever owns them. */
    Map<MerchantId, Set<String>> fingerprintsInFlight();
}
