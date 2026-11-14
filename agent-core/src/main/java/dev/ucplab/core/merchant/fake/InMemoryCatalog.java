package dev.ucplab.core.merchant.fake;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * An in-memory {@link CatalogGateway} over a fixed list of products, with the lab merchant's behaviour (FR1, 6.2): a
 * search matches the title, case-insensitively, in catalog order, and says whether more matched than the limit; a lookup
 * leaves unknown ids out; an unknown product is a {@code not_found} ProtocolError. It never fails like a network. Product
 * text is returned as the merchant gave it: the port carries untrusted data and does not interpret it (6.4).
 */
public final class InMemoryCatalog implements CatalogGateway {

    private final List<CatalogProduct> products;

    public InMemoryCatalog(List<CatalogProduct> products) {
        this.products = List.copyOf(Objects.requireNonNull(products, "products"));
    }

    @Override
    public CallOutcome<CatalogPage> search(CatalogSearch request) {
        Objects.requireNonNull(request, "request");
        String query = request.query().toLowerCase(Locale.ROOT);
        List<CatalogProduct> matches = products.stream()
                .filter(p -> p.title().toLowerCase(Locale.ROOT).contains(query)).toList();
        List<CatalogProduct> page = matches.stream().limit(request.limit()).toList();
        return new CallOutcome.Ok<>(200, new CatalogPage(page, matches.size() > page.size()), List.of());
    }

    @Override
    public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ids must not be empty");
        }
        return new CallOutcome.Ok<>(200, products.stream().filter(p -> ids.contains(p.id())).toList(), List.of());
    }

    @Override
    public CallOutcome<CatalogProduct> product(String id) {
        Objects.requireNonNull(id, "id");
        Optional<CatalogProduct> found = products.stream().filter(p -> p.id().equals(id)).findFirst();
        return found.<CallOutcome<CatalogProduct>>map(p -> new CallOutcome.Ok<>(200, p, List.of()))
                .orElseGet(() -> new CallOutcome.ProtocolError<>(200, "not_found", "no product " + id,
                        Optional.empty()));
    }
}
