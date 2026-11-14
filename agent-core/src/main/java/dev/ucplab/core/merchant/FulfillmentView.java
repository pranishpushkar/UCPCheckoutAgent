package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The fulfillment part of a merchant quote (2.1, 8.1; H1 C5): the shipping methods, the destinations the merchant offers
 * (their ids and country, nothing else: the street, locality, region and postal code of an address are never parsed, so
 * they can never reach an event, a log line or an approval), the groups with their options and prices, and what is
 * selected so far. Read from the merchant's response, never built by the agent.
 */
public record FulfillmentView(List<Method> methods) {

    public FulfillmentView {
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
    }

    /** One fulfillment method of the checkout (for example {@code shipping}). */
    public record Method(
            String id, String type, List<String> lineItemIds, List<Destination> destinations,
            Optional<String> selectedDestinationId, List<Group> groups) {

        public Method {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            lineItemIds = List.copyOf(Objects.requireNonNull(lineItemIds, "lineItemIds"));
            destinations = List.copyOf(Objects.requireNonNull(destinations, "destinations"));
            Objects.requireNonNull(selectedDestinationId, "selectedDestinationId");
            groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        }
    }

    /** A destination the merchant offers: its id, and the country (the one address field the approval snapshot shows, 9.2). */
    public record Destination(String id, Optional<String> country) {

        public Destination {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(country, "country");
        }
    }

    /** The options for a group of line items, and the one selected. */
    public record Group(String id, List<String> lineItemIds, List<Option> options, Optional<String> selectedOptionId) {

        public Group {
            Objects.requireNonNull(id, "id");
            lineItemIds = List.copyOf(Objects.requireNonNull(lineItemIds, "lineItemIds"));
            options = List.copyOf(Objects.requireNonNull(options, "options"));
            Objects.requireNonNull(selectedOptionId, "selectedOptionId");
        }
    }

    /** A shipping option: id, the merchant's label (untrusted text) and its price in minor units. */
    public record Option(String id, String title, long priceMinor) {

        public Option {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
        }
    }
}
