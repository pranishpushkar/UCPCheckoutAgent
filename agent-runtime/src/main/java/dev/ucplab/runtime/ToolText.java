package dev.ucplab.runtime;

import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogVariant;
import java.util.Set;
import java.util.regex.Pattern;

/** Strings a tool puts in a result: our own codes and ids as they are, anything else wrapped as untrusted (6.4). */
final class ToolText {

    /** A failure reason or state as the orchestrator writes them. */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_]{1,80}");
    /** DefaultOrchestrator's prefix for a reason built from a merchant's error code. */
    private static final String MERCHANT_ERROR = "merchant_error_";
    /** DefaultOrchestrator's prefix for a COMPLETING error code that names no cancel row. */
    private static final String COMPLETE_ERROR = "complete_error:";
    /** What the model sees for a merchant code not on {@link #MERCHANT_CODES} (8.1). */
    static final String UNLISTED_CODE = "merchant_error";
    /**
     * The merchant codes that reach the model as they are (8.1): the lab merchant's, the codes 7.2 names, and the
     * Shopify buyer codes the H5 spike recorded.
     */
    static final Set<String> MERCHANT_CODES = Set.of(
            "already_completed", "card_declined", "checkout_busy", "checkout_canceled", "checkout_not_modifiable",
            "complete_in_progress", "idempotency_key_reuse", "internal_error", "invalid_request", "invalid_token",
            "item_unavailable", "missing_header", "not_found", "not_ready", "out_of_stock", "payment_declined",
            "price_changed", "quantity_adjusted",
            "buyer_identity_contact_method_required", "delivery_address_required",
            "delivery_no_delivery_available_for_merchandise_line");

    private ToolText() {
    }

    /** The merchant's title for a variant: the product title, plus the variant title when the product has several. */
    static String title(CatalogProduct product, CatalogVariant variant) {
        String title = product.variants().size() == 1 || variant.title().equals(product.title())
                ? product.title() : product.title() + " - " + variant.title();
        return UntrustedText.wrap("merchant", "title", title);
    }

    /** An id the merchant sent (an item or order id): as it is when it looks like an id, else wrapped. */
    static String merchantId(String field, String value) {
        return Args.ITEM_ID.matcher(value).matches() ? value : UntrustedText.wrap("merchant", field, value);
    }

    /**
     * A failure reason as the model sees it (8.1): a reason that carries a merchant's code ({@code merchant_error_<code>}
     * or {@code complete_error:<code>}) as it is when the code is on {@link #MERCHANT_CODES}, else
     * {@value #UNLISTED_CODE}; any other reason of ours as it is; anything else wrapped. The stored reason is unchanged.
     */
    static String failureReason(String value) {
        for (String prefix : new String[] {MERCHANT_ERROR, COMPLETE_ERROR}) {
            if (value.startsWith(prefix)) {
                return MERCHANT_CODES.contains(value.substring(prefix.length())) ? value : UNLISTED_CODE;
            }
        }
        return CODE.matcher(value).matches() ? value : UntrustedText.wrap("merchant", "failure_reason", value);
    }
}
