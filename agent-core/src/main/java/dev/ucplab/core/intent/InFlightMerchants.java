package dev.ucplab.core.intent;

import dev.ucplab.core.merchant.MerchantId;
import java.util.Set;

/**
 * System read beside {@link RecoveryScan} (DESIGN 19, 18.1): the merchants of every intent that is not terminal, across
 * owners. Startup uses it to refuse a merchants file that drops a merchant whose intents are still in flight, because
 * those intents could then never reconcile. It takes no lease, changes nothing and caches nothing (section 20); like
 * {@code RecoveryScan}, it is the only kind of read that crosses owners.
 */
public interface InFlightMerchants {

    /** The distinct merchant ids of intents in a non-terminal state, whoever owns them. */
    Set<MerchantId> merchantsInFlight();
}
