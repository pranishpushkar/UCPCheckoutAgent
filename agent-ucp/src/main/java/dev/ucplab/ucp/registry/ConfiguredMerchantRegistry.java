package dev.ucplab.ucp.registry;

import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.ucp.UcpHttpGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The registry of {@code MERCHANTS_FILE} (DESIGN 19). A merchant is ACTIVE when its file status is {@code active} and its
 * last discovery check passed; otherwise it is SUSPENDED with a fixed reason ({@link #reason}) and is left out of
 * {@link #active()}, so P1 denies it. Only the gateway's own discovery is used, so the profile cache stays the gateway's
 * (section 20); this class keeps a status and a reason, nothing else.
 *
 * <p>The startup check asks every merchant at once ({@link #start}); a failing merchant never stops startup. A merchant
 * SUSPENDED by a check is re-checked on demand, at most every {@value #RECHECK_SECONDS} s (decision 2 of m6a): the caller
 * that wins a compare-and-set starts the check on a virtual thread of its own and, like every other caller, returns the
 * last known status without waiting. No lock is held, and nothing here blocks on a merchant after startup (7.8). A
 * merchant the file marks suspended is never asked. {@link #profile} goes to the merchant's gateway whatever its status,
 * so an in-flight intent at a SUSPENDED merchant still reconciles.
 */
public final class ConfiguredMerchantRegistry implements MerchantRegistry {

    /** The fixed reasons of a SUSPENDED merchant (8.5, 26.2); public so a caller maps by constant, never by text. */
    public static final String INCOMPATIBLE_VERSION = UcpHttpGateway.INCOMPATIBLE_VERSION;
    public static final String CAPABILITY_MISSING = UcpHttpGateway.CAPABILITY_MISSING;
    public static final String ENDPOINT_NOT_ALLOWED = UcpHttpGateway.ENDPOINT_NOT_ALLOWED;
    public static final String HANDLER_MISSING = UcpHttpGateway.HANDLER_MISSING;
    public static final String INVALID_PROFILE = UcpHttpGateway.INVALID_PROFILE;
    public static final String UNREACHABLE = UcpHttpGateway.UNREACHABLE;
    /** The file says {@code status: suspended}. */
    public static final String SUSPENDED_IN_FILE = "suspended_in_file";

    static final int RECHECK_SECONDS = 30;
    private static final Duration RECHECK = Duration.ofSeconds(RECHECK_SECONDS);
    private static final List<String> KNOWN_REASONS = List.of(INCOMPATIBLE_VERSION, CAPABILITY_MISSING,
            ENDPOINT_NOT_ALLOWED, HANDLER_MISSING, INVALID_PROFILE, UNREACHABLE);

    /** What the registry last learned about one merchant; replaced as a whole, so a reader never sees half of it. */
    private record State(MerchantStatus status, Optional<String> reason, Instant checkedAt) {
    }

    private final class Slot {
        final MerchantSettings settings;
        final AtomicReference<State> state;
        final AtomicBoolean checking = new AtomicBoolean();

        Slot(MerchantSettings settings, State initial) {
            this.settings = settings;
            this.state = new AtomicReference<>(initial);
        }
    }

    private final Map<MerchantId, Slot> slots = new LinkedHashMap<>();
    private final Function<MerchantId, MerchantGateway> gateways;
    private final Clock clock;

    private ConfiguredMerchantRegistry(List<MerchantSettings> settings, Function<MerchantId, MerchantGateway> gateways,
                                       Clock clock) {
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.clock = Objects.requireNonNull(clock, "clock");
        Instant now = clock.instant();
        for (MerchantSettings s : Objects.requireNonNull(settings, "settings")) {
            State initial = s.fileStatus() == MerchantStatus.ACTIVE
                    ? new State(MerchantStatus.SUSPENDED, Optional.of(UNREACHABLE), now)
                    : new State(MerchantStatus.SUSPENDED, Optional.of(SUSPENDED_IN_FILE), now);
            if (slots.put(s.id(), new Slot(s, initial)) != null) {
                throw new IllegalArgumentException("a merchant id is listed twice");
            }
        }
    }

    /**
     * Builds the registry and runs the startup check: every merchant whose file status is active is asked at once, each on
     * its own virtual thread, and this returns when all have answered (each is bounded by its gateway's own timeouts).
     */
    public static ConfiguredMerchantRegistry start(List<MerchantSettings> settings,
                                                   Function<MerchantId, MerchantGateway> gateways, Clock clock) {
        ConfiguredMerchantRegistry registry = new ConfiguredMerchantRegistry(settings, gateways, clock);
        List<Thread> checks = new ArrayList<>();
        for (Slot slot : registry.slots.values()) {
            if (slot.settings.fileStatus() == MerchantStatus.ACTIVE) {
                slot.checking.set(true);
                checks.add(Thread.ofVirtual().start(() -> registry.check(slot)));
            }
        }
        for (Thread t : checks) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return registry;
    }

    @Override
    public List<MerchantEntry> active() {
        List<MerchantEntry> out = new ArrayList<>();
        for (Slot slot : slots.values()) {
            State state = current(slot);
            if (state.status() == MerchantStatus.ACTIVE) {
                out.add(slot.settings.entry(MerchantStatus.ACTIVE));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<MerchantEntry> find(MerchantId id) {
        Slot slot = slots.get(Objects.requireNonNull(id, "id"));
        return slot == null ? Optional.empty() : Optional.of(slot.settings.entry(current(slot).status()));
    }

    /** The merchant's profile from its own gateway, whatever its status (19: in-flight intents keep reconciling). */
    @Override
    public MerchantProfile profile(MerchantId id) {
        if (!slots.containsKey(Objects.requireNonNull(id, "id"))) {
            throw new IllegalArgumentException("unknown merchant");
        }
        return gateways.apply(id).discover(id);
    }

    /** Why the merchant is SUSPENDED, as one of the constants above; empty if it is ACTIVE or unknown. Never triggers a check. */
    public Optional<String> reason(MerchantId id) {
        Slot slot = slots.get(Objects.requireNonNull(id, "id"));
        return slot == null ? Optional.empty() : slot.state.get().reason();
    }

    /** The last known state, after starting a re-check if this merchant is SUSPENDED by a check and one is due. */
    private State current(Slot slot) {
        State state = slot.state.get();
        if (state.status() == MerchantStatus.SUSPENDED && slot.settings.fileStatus() == MerchantStatus.ACTIVE
                && !clock.instant().isBefore(state.checkedAt().plus(RECHECK))
                && slot.checking.compareAndSet(false, true)) {
            try {
                Thread.ofVirtual().start(() -> check(slot));
            } catch (RuntimeException | Error e) {
                slot.checking.set(false);
                throw e;
            }
        }
        return state;
    }

    /** One discovery, run off every caller's thread; the slot's flag is held until it ends. */
    private void check(Slot slot) {
        try {
            MerchantId id = slot.settings.id();
            State result;
            try {
                gateways.apply(id).discover(id);
                result = new State(MerchantStatus.ACTIVE, Optional.empty(), clock.instant());
            } catch (DiscoveryFailedException e) {
                result = new State(MerchantStatus.SUSPENDED, Optional.of(known(e.reason())), clock.instant());
            } catch (RuntimeException e) {
                result = new State(MerchantStatus.SUSPENDED, Optional.of(UNREACHABLE), clock.instant());
            }
            slot.state.set(result);
        } finally {
            slot.checking.set(false);
        }
    }

    private static String known(String code) {
        return KNOWN_REASONS.contains(code) ? code : UNREACHABLE;
    }
}
