package dev.ucplab.core.merchant;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Helpers for the instrument-by-merchant function the orchestrator takes (19: the handler is the merchant's). */
public final class MerchantInstruments {

    private MerchantInstruments() {
    }

    /** The instrument of each merchant in {@code byMerchant}; any other merchant throws {@link NoInstrumentException}. */
    public static Function<MerchantId, PaymentInstrument> of(Map<MerchantId, PaymentInstrument> byMerchant) {
        Map<MerchantId, PaymentInstrument> copy = Map.copyOf(Objects.requireNonNull(byMerchant, "byMerchant"));
        return id -> {
            PaymentInstrument instrument = copy.get(Objects.requireNonNull(id, "id"));
            if (instrument == null) {
                throw new NoInstrumentException(id);
            }
            return instrument;
        };
    }
}
