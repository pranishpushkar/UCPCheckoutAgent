package dev.ucplab.core.merchant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An in-memory {@link MerchantRegistry} of fixed entries (section 19): only ACTIVE merchants are listed as active, and
 * {@link #find} reports a SUSPENDED merchant as it is. It reads no profile: the tools that use it never ask for one.
 */
public final class InMemoryMerchantRegistry implements MerchantRegistry {

    private final List<MerchantEntry> entries;

    public InMemoryMerchantRegistry(List<MerchantEntry> entries) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    @Override
    public List<MerchantEntry> active() {
        return entries.stream().filter(e -> e.status() == MerchantStatus.ACTIVE).toList();
    }

    @Override
    public Optional<MerchantEntry> find(MerchantId id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    @Override
    public MerchantProfile profile(MerchantId id) {
        throw new UnsupportedOperationException("this registry reads no profiles");
    }
}
