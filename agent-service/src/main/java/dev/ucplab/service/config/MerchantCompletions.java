package dev.ucplab.service.config;

import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantId;

/**
 * A merchant's {@code completion} as the merchants file configures it (19): the entry the orchestrator drives its intents
 * by. A merchant the file does not name is {@code agent}, as the orchestrator reads a missing entry (7.2).
 */
@FunctionalInterface
public interface MerchantCompletions {

    Completion of(MerchantId merchant);
}
