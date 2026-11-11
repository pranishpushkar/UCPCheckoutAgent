package dev.ucplab.core.merchant;

import java.util.List;

/**
 * Port to one merchant's UCP catalog (FR1, 6.2 {@code search_catalog}). Implemented by agent-ucp. Like
 * {@link MerchantGateway} it reports network failure as an outcome, never an exception (8.1), and it is bound to one
 * merchant at construction.
 *
 * <p>These are reads: there is no idempotency key, and a failed attempt is retried as a {@code get} is (8.2). Their
 * outcomes are {@code Ok}, {@code NotSent}, {@code Uncertain} (the retries ran out, which for a read only means no
 * answer) and {@code ProtocolError}; {@code Rejected} never occurs. A body the client cannot read is a
 * {@code ProtocolError}. Results are untrusted merchant data and are never a basis for payment: the quote at checkout
 * is authoritative (section 20).
 */
public interface CatalogGateway {

    /** Products matching the query, best first. No match is an {@code Ok} with an empty page. */
    CallOutcome<CatalogPage> search(CatalogSearch request);

    /**
     * Products for product or variant ids. An id the merchant does not know is left out, so the list may be shorter
     * than {@code ids}. {@code ids} must not be empty.
     *
     * @throws IllegalArgumentException if {@code ids} is empty
     */
    CallOutcome<List<CatalogProduct>> lookup(List<String> ids);

    /**
     * One product. An id the merchant does not know is a {@code ProtocolError} with code {@code not_found}, not retried
     * (the merchant answers it with HTTP 200 and {@code ucp.status} error, an unrecoverable business outcome).
     */
    CallOutcome<CatalogProduct> product(String id);
}
