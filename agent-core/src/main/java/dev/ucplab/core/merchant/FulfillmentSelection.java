package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The fulfillment part of an update (8.4, H1 C6): the method (its id and the line items it covers), the destination
 * selected so far, and the option selected per group. Built only from ids the merchant's previous response offered, never
 * from an address, so there is nothing personal in it.
 */
public record FulfillmentSelection(
        String methodId, String type, List<String> lineItemIds, Optional<String> destinationId,
        List<GroupSelection> groups) {

    public FulfillmentSelection {
        Objects.requireNonNull(methodId, "methodId");
        Objects.requireNonNull(type, "type");
        lineItemIds = List.copyOf(Objects.requireNonNull(lineItemIds, "lineItemIds"));
        Objects.requireNonNull(destinationId, "destinationId");
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
    }

    /** The method a {@link FulfillmentPolicy} asks for first. */
    public record MethodChoice(String id, String type) {

        public MethodChoice {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
        }
    }

    /** One group's option choice: the group's id, the line items it covers, and the option id. */
    public record GroupSelection(String groupId, List<String> lineItemIds, String optionId) {

        public GroupSelection {
            Objects.requireNonNull(groupId, "groupId");
            lineItemIds = List.copyOf(Objects.requireNonNull(lineItemIds, "lineItemIds"));
            Objects.requireNonNull(optionId, "optionId");
        }
    }
}
