package dev.ucplab.runtime;

import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogVariant;
import java.util.regex.Pattern;

/** Strings a tool puts in a result: our own codes and ids as they are, anything else wrapped as untrusted (6.4). */
final class ToolText {

    /** A failure reason or state as the orchestrator writes them. */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_]{1,80}");
    /** DefaultOrchestrator's prefix for a reason built from a merchant's error code. */
    private static final String MERCHANT_ERROR = "merchant_error_";

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
        return Args.ID.matcher(value).matches() ? value : UntrustedText.wrap("merchant", field, value);
    }

    /**
     * A failure reason: ours as it is, else wrapped. A reason that carries a merchant's error code
     * ({@code merchant_error_<code>}) is always wrapped, because the merchant chose the code.
     */
    static String failureReason(String value) {
        return CODE.matcher(value).matches() && !value.startsWith(MERCHANT_ERROR) ? value
                : UntrustedText.wrap("merchant", "failure_reason", value);
    }
}
