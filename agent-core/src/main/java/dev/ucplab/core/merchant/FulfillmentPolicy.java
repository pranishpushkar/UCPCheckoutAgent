package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Optional;

/**
 * Chooses the shipping selection when a merchant needs one before it is ready (7.2, H1 C6). Chosen by configuration and
 * never by the model (I6). It sees only what the merchant offered: ids, a destination's country and an option's price.
 * No address is held, sent or stored by the agent: the destination is one the merchant itself listed.
 */
public interface FulfillmentPolicy {

    /** The id and type of the method to ask for first. */
    FulfillmentSelection.MethodChoice method();

    /** The destination to select among those the merchant offers, if any. */
    Optional<String> destination(List<FulfillmentView.Destination> offered);

    /** The option to select in a group, if any. */
    Optional<String> option(List<FulfillmentView.Option> offered);

    /** The default: the first destination the merchant offers and the cheapest option (the first of equal prices). */
    static FulfillmentPolicy cheapest() {
        return new FulfillmentPolicy() {
            @Override
            public FulfillmentSelection.MethodChoice method() {
                return new FulfillmentSelection.MethodChoice("method_1", "shipping");
            }

            @Override
            public Optional<String> destination(List<FulfillmentView.Destination> offered) {
                return offered.stream().findFirst().map(FulfillmentView.Destination::id);
            }

            @Override
            public Optional<String> option(List<FulfillmentView.Option> offered) {
                Optional<FulfillmentView.Option> best = Optional.empty();
                for (FulfillmentView.Option o : offered) {
                    if (best.isEmpty() || o.priceMinor() < best.get().priceMinor()) {
                        best = Optional.of(o);
                    }
                }
                return best.map(FulfillmentView.Option::id);
            }
        };
    }
}
