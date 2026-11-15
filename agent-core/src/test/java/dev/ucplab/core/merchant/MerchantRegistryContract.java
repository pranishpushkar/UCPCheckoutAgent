package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What every {@link MerchantRegistry} must answer (section 19, Appendix B, P1): a merchant is found by its id, only ACTIVE
 * merchants are listed as active, and a SUSPENDED one is never reported active. A subclass builds a registry holding
 * exactly {@code entries}; one that can hold only a single merchant skips the tests that need two
 * ({@code assumeTrue(entries.size() == 1)}).
 */
public abstract class MerchantRegistryContract {

    protected static final MerchantEntry ALPHA = entry("alpha", MerchantStatus.ACTIVE);
    protected static final MerchantEntry BETA = entry("beta", MerchantStatus.ACTIVE);
    protected static final MerchantEntry GAMMA_SUSPENDED = entry("gamma", MerchantStatus.SUSPENDED);

    private static MerchantEntry entry(String id, MerchantStatus status) {
        return new MerchantEntry(new MerchantId(id), URI.create("http://127.0.0.1:9001"), "Shop " + id, status);
    }

    /** A registry that holds exactly {@code entries}. */
    protected abstract MerchantRegistry create(List<MerchantEntry> entries);

    // Covers: 19, Appendix B (an active merchant is found by its id, as registered, and is listed as active)
    @Test
    void find_anActiveMerchant_returnsItAndListsItActive() {
        MerchantRegistry registry = create(List.of(ALPHA));

        assertThat(registry.find(ALPHA.id())).contains(ALPHA);
        assertThat(registry.active()).containsExactly(ALPHA);
    }

    // Covers: 19, P1 (an id the registry does not hold finds nothing)
    @Test
    void find_anUnknownId_isEmpty() {
        MerchantRegistry registry = create(List.of(ALPHA));

        assertThat(registry.find(new MerchantId("nobody"))).isEmpty();
    }

    // Covers: 19, P1 (a SUSPENDED merchant is not listed as active, and is never found as ACTIVE: it is absent, or reported
    // SUSPENDED. Only ACTIVE merchants take new intents)
    @Test
    void aSuspendedMerchant_isNeverActive() {
        MerchantRegistry registry = create(List.of(GAMMA_SUSPENDED));

        assertThat(registry.active()).isEmpty();
        Optional<MerchantEntry> found = registry.find(GAMMA_SUSPENDED.id());
        assertThat(found.map(MerchantEntry::status)).isIn(Optional.empty(), Optional.of(MerchantStatus.SUSPENDED));
    }

    // Covers: 19, P1 (with several merchants, active lists exactly the ACTIVE ones, and each is found by its own id)
    @Test
    void severalMerchants_activeListsOnlyTheActiveOnes() {
        MerchantRegistry registry = create(List.of(ALPHA, GAMMA_SUSPENDED, BETA));

        assertThat(registry.active()).containsExactlyInAnyOrder(ALPHA, BETA);
        assertThat(registry.find(ALPHA.id())).contains(ALPHA);
        assertThat(registry.find(BETA.id())).contains(BETA);
        assertThat(registry.find(GAMMA_SUSPENDED.id()).map(MerchantEntry::status))
                .isIn(Optional.empty(), Optional.of(MerchantStatus.SUSPENDED));
    }

    // Covers: 19, P1, Appendix B (active() and find() never disagree: a merchant is listed active exactly when find reports it
    // ACTIVE, so a policy that reads find and a tool that reads active see the same merchants)
    @Test
    void active_andFind_agreeForEveryMerchant() {
        MerchantRegistry registry = create(List.of(ALPHA, GAMMA_SUSPENDED, BETA));

        for (MerchantEntry e : List.of(ALPHA, GAMMA_SUSPENDED, BETA)) {
            boolean listed = registry.active().stream().anyMatch(a -> a.id().equals(e.id()));
            boolean foundActive = registry.find(e.id()).map(f -> f.status() == MerchantStatus.ACTIVE).orElse(false);
            assertThat(listed).as("%s listed active", e.id()).isEqualTo(foundActive);
        }
    }
}
