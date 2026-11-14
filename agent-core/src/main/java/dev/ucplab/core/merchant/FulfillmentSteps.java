package dev.ucplab.core.merchant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The selection updates that take a checkout from {@code incomplete} to {@code ready_for_complete} (7.2, 8.4, H1 C6),
 * each built from the previous response only: the line item ids, the method, the destination id and the group id it
 * showed. A pure function of that response and the {@link FulfillmentPolicy}; it holds no address.
 */
public final class FulfillmentSteps {

    private FulfillmentSteps() {
    }

    /**
     * The next selection, or empty when nothing is left to choose: the method is asked for first (with the line items
     * the merchant gave ids to), then the destination, then the option of each group.
     *
     * @param last the fulfillment part of the previous response, empty if it had none
     * @param lineItemIds the merchant's ids of the checkout's lines, used to ask for the method
     */
    public static Optional<FulfillmentSelection> next(
            Optional<FulfillmentView> last, List<String> lineItemIds, FulfillmentPolicy policy) {
        Objects.requireNonNull(last, "last");
        Objects.requireNonNull(lineItemIds, "lineItemIds");
        Objects.requireNonNull(policy, "policy");
        if (last.isEmpty() || last.get().methods().isEmpty()) {
            FulfillmentSelection.MethodChoice m = policy.method();
            return Optional.of(new FulfillmentSelection(m.id(), m.type(), lineItemIds, Optional.empty(), List.of()));
        }
        FulfillmentView.Method method = last.get().methods().get(0);
        if (method.selectedDestinationId().isEmpty()) {
            return policy.destination(method.destinations()).map(d ->
                    new FulfillmentSelection(method.id(), method.type(), method.lineItemIds(), Optional.of(d), List.of()));
        }
        boolean pending = method.groups().stream().anyMatch(g -> g.selectedOptionId().isEmpty());
        if (!pending) {
            return Optional.empty();
        }
        List<FulfillmentSelection.GroupSelection> groups = new ArrayList<>();
        for (FulfillmentView.Group g : method.groups()) {
            Optional<String> option = g.selectedOptionId().isPresent() ? g.selectedOptionId() : policy.option(g.options());
            if (option.isEmpty()) {
                return Optional.empty();
            }
            groups.add(new FulfillmentSelection.GroupSelection(g.id(), g.lineItemIds(), option.get()));
        }
        return Optional.of(new FulfillmentSelection(method.id(), method.type(), method.lineItemIds(),
                method.selectedDestinationId(), groups));
    }

    /**
     * The selection the last response already shows, to be repeated by a full-replacement update that is not a selection
     * step (a re-quote): empty if nothing is selected yet.
     */
    public static Optional<FulfillmentSelection> carry(Optional<FulfillmentView> last) {
        Objects.requireNonNull(last, "last");
        if (last.isEmpty() || last.get().methods().isEmpty()) {
            return Optional.empty();
        }
        FulfillmentView.Method method = last.get().methods().get(0);
        if (method.selectedDestinationId().isEmpty()) {
            return Optional.empty();
        }
        List<FulfillmentSelection.GroupSelection> groups = method.groups().stream()
                .filter(g -> g.selectedOptionId().isPresent())
                .map(g -> new FulfillmentSelection.GroupSelection(g.id(), g.lineItemIds(), g.selectedOptionId().get()))
                .toList();
        return Optional.of(new FulfillmentSelection(method.id(), method.type(), method.lineItemIds(),
                method.selectedDestinationId(), groups));
    }
}
