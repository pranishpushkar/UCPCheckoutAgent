package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Seeded generator of one schedule for the property tests (14.1). {@link #generate} depends only on the seed, so the
 * same seed always yields the same schedule; nothing else is random and no wall clock is read.
 *
 * <p><b>Bounds</b> (14.1), all constants below:
 * <ul>
 *   <li>up to {@link #MAX_SLOTS} baskets and {@link #MAX_ENTRIES} entries per schedule;
 *   <li>up to {@link #MAX_CALL_FAULTS} per-call faults, each on one of the first {@link #MAX_NTH} calls of its kind;
 *   <li>up to {@link #MAX_CRASHES} crashes, and up to {@link #MAX_DECISIONS_PER_SLOT} decisions per basket;
 *   <li>proxy holds ({@code DelayForward}) of 1 s to {@link #MAX_HOLD};
 *   <li>waits of up to {@link #MAX_SHORT_WAIT} in 250 ms steps, up to {@link #MAX_MEDIUM_WAIT}, or between
 *       {@link #MIN_LONG_WAIT} and {@link #MAX_LONG_WAIT} (11 minutes);
 *   <li>merchant-side prices from {@link #PRICES_ITEM_123} and {@link #PRICES_ITEM_456}, stock from {@link #STOCKS}.
 * </ul>
 *
 * <p>Each schedule is drawn from one of three profiles: {@code MIXED} (anything), {@code REQUOTE} (price and stock
 * changes anchored to {@code complete} calls with repeated approvals, for P7 and P8, sometimes with the response lost
 * so the re-quote comes through reconciliation) and {@code CRASH} (crash points of 7.3 plus delivery faults). Per slot
 * the user's story is a proposal followed by zero or more decisions; the stories are interleaved and noise (waits,
 * merchant-side events, stale tokens, extra decisions) is sprinkled in. All sizes and values stay within the bounds
 * below.
 */
final class ScheduleGenerator {

    static final int MAX_SLOTS = ScheduleExecutor.SLOT_LINES.size();
    static final int MAX_ENTRIES = 40;
    static final int MAX_CALL_FAULTS = 6;
    /** Faults target one of the first MAX_NTH calls of a kind, counted over the whole run. */
    static final int MAX_NTH = 6;
    static final int MAX_CRASHES = 2;
    /** Three approvals reach the P8 cap of 2 re-quotes (S16); one more probes past it. */
    static final int MAX_DECISIONS_PER_SLOT = 4;
    static final Duration MAX_SHORT_WAIT = Duration.ofSeconds(5);
    /** Covers the 20 s quiet period, the 30 s lease and the 60 s reconciliation deadline (7.5, 7.6). */
    static final Duration MAX_MEDIUM_WAIT = Duration.ofSeconds(75);
    /** Around the 10 minute approval TTL (9.2). */
    static final Duration MIN_LONG_WAIT = Duration.ofMinutes(9);
    static final Duration MAX_LONG_WAIT = Duration.ofMinutes(11);
    /** Proxy holds go past the 10 s complete timeout and, up to here, past the 20 s quiet period too. */
    static final Duration MAX_HOLD = Duration.ofSeconds(30);
    static final long[] PRICES_ITEM_123 = {2300, 2500, 2600, 2800};
    static final long[] PRICES_ITEM_456 = {1100, 1200, 1300};
    static final int[] STOCKS = {0, 1, 2, 3, 100};
    private static final String[] ITEMS = {"item_123", "item_456"};
    private static final MerchantCall[] FAULT_CALLS = {
        MerchantCall.COMPLETE, MerchantCall.COMPLETE, MerchantCall.COMPLETE, MerchantCall.COMPLETE,
        MerchantCall.GET, MerchantCall.GET, MerchantCall.CREATE, MerchantCall.CREATE, MerchantCall.UPDATE,
        MerchantCall.CANCEL};
    private static final MerchantCall[] CRASH_CALLS = {
        MerchantCall.COMPLETE, MerchantCall.COMPLETE, MerchantCall.GET, MerchantCall.GET, MerchantCall.CREATE,
        MerchantCall.UPDATE, MerchantCall.CANCEL};

    private enum Profile { MIXED, REQUOTE, CRASH }

    private final long seed;
    private SplittableRandom random;

    ScheduleGenerator(long seed) {
        this.seed = seed;
    }

    long seed() {
        return seed;
    }

    /** The schedule for this seed; every call returns an equal one. */
    Schedule generate() {
        random = new SplittableRandom(seed);
        Profile profile = Profile.values()[random.nextInt(Profile.values().length)];
        int slots = 1 + random.nextInt(profile == Profile.REQUOTE ? 2 : MAX_SLOTS);

        List<List<ScheduleEntry>> stories = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            stories.add(story(slot, profile));
        }
        List<ScheduleEntry> entries = interleave(stories);

        int noise = random.nextInt(profile == Profile.MIXED ? 10 : 5);
        for (int i = 0; i < noise; i++) {
            insert(entries, noise(slots));
        }
        int faults = random.nextInt(MAX_CALL_FAULTS + 1);
        for (int i = 0; i < faults; i++) {
            insert(entries, new ScheduleEntry.CallFault(pick(FAULT_CALLS), 1 + random.nextInt(MAX_NTH), deliveryFault()));
        }
        if (profile == Profile.REQUOTE) {
            requoteAnchors(entries, slots);
        }
        int crashes = switch (profile) {
            case CRASH -> 1 + random.nextInt(MAX_CRASHES);
            case MIXED -> random.nextInt(5) == 0 ? 1 : 0;
            case REQUOTE -> 0;
        };
        for (int i = 0; i < crashes; i++) {
            insert(entries, new ScheduleEntry.Crash(pick(CRASH_CALLS), random.nextBoolean()));
        }
        return new Schedule(entries.size() > MAX_ENTRIES ? entries.subList(0, MAX_ENTRIES) : entries);
    }

    /** One user's story: propose (sometimes twice, 7.7), then decisions separated by waits. */
    private List<ScheduleEntry> story(int slot, Profile profile) {
        List<ScheduleEntry> story = new ArrayList<>();
        story.add(new ScheduleEntry.Propose(slot));
        if (random.nextInt(10) == 0) {
            story.add(new ScheduleEntry.Propose(slot));
        }
        int decisions = profile == Profile.REQUOTE
                ? 1 + random.nextInt(MAX_DECISIONS_PER_SLOT)
                : random.nextInt(7) == 0 ? 0 : 1 + random.nextInt(2);
        for (int i = 0; i < decisions; i++) {
            story.add(wait(random.nextInt(4) == 0 ? mediumWait() : shortWait()));
            boolean reject = random.nextInt(profile == Profile.REQUOTE && i > 0 ? 5 : 12) == 0;
            story.add(reject ? new ScheduleEntry.Reject(slot) : new ScheduleEntry.Approve(slot));
        }
        return story;
    }

    /** P7, P8, S5, S6, S16: the price or stock changes just before one of the first complete calls. */
    private void requoteAnchors(List<ScheduleEntry> entries, int slots) {
        int anchors = 1 + random.nextInt(3);
        for (int nth = 1; nth <= anchors; nth++) {
            int call = random.nextInt(4) == 0 ? nth + 1 : nth;
            String item = ITEMS[random.nextInt(slots == 1 ? 1 : ITEMS.length)];
            Fault change = random.nextInt(6) == 0
                    ? new Fault.StockChange(item, pick(STOCKS))
                    : new Fault.PriceChange(item, price(item));
            insert(entries, new ScheduleEntry.CallFault(MerchantCall.COMPLETE, call, change));
            if (random.nextInt(3) == 0) {
                // The incomplete answer is lost: the re-quote comes from RECONCILING observing incomplete (7.2).
                Fault lost = random.nextBoolean() ? new Fault.CommitThenDrop() : new Fault.DelayResponse();
                insert(entries, new ScheduleEntry.CallFault(MerchantCall.COMPLETE, call, lost));
            }
        }
    }

    private ScheduleEntry noise(int slots) {
        int slot = random.nextInt(slots);
        return switch (random.nextInt(9)) {
            case 0, 1 -> wait(shortWait());
            case 2 -> wait(mediumWait());
            case 3 -> wait(longWait());
            case 4 -> {
                String item = ITEMS[random.nextInt(ITEMS.length)];
                yield new ScheduleEntry.SetPrice(item, price(item));
            }
            case 5 -> new ScheduleEntry.SetStock(ITEMS[random.nextInt(ITEMS.length)], pick(STOCKS));
            case 6 -> new ScheduleEntry.ApproveStale(slot);
            case 7 -> new ScheduleEntry.Approve(slot);
            default -> new ScheduleEntry.Reject(slot);
        };
    }

    /** The four per-call faults: not sent, commit then drop the response, delay past the timeout, duplicate. */
    private Fault deliveryFault() {
        return switch (random.nextInt(5)) {
            case 0 -> new Fault.NotSent();
            case 1 -> new Fault.CommitThenDrop();
            case 2 -> new Fault.DelayResponse();
            case 3 -> new Fault.DelayForward(Duration.ofSeconds(1 + random.nextInt((int) MAX_HOLD.toSeconds())));
            default -> new Fault.Duplicate();
        };
    }

    private Duration shortWait() {
        return Duration.ofMillis(250L * random.nextInt((int) (MAX_SHORT_WAIT.toMillis() / 250) + 1));
    }

    private Duration mediumWait() {
        long from = MAX_SHORT_WAIT.toSeconds();
        return Duration.ofSeconds(from + random.nextLong(MAX_MEDIUM_WAIT.toSeconds() - from + 1));
    }

    private Duration longWait() {
        long span = MAX_LONG_WAIT.minus(MIN_LONG_WAIT).toSeconds() / 10;
        return MIN_LONG_WAIT.plusSeconds(10 * random.nextLong(span + 1));
    }

    private static ScheduleEntry wait(Duration d) {
        return new ScheduleEntry.Wait(d);
    }

    private long price(String item) {
        long[] prices = item.equals("item_123") ? PRICES_ITEM_123 : PRICES_ITEM_456;
        return prices[random.nextInt(prices.length)];
    }

    private int pick(int[] values) {
        return values[random.nextInt(values.length)];
    }

    private MerchantCall pick(MerchantCall[] values) {
        return values[random.nextInt(values.length)];
    }

    /** A random merge that keeps each story's own order. */
    private List<ScheduleEntry> interleave(List<List<ScheduleEntry>> stories) {
        List<ScheduleEntry> merged = new ArrayList<>();
        int[] next = new int[stories.size()];
        int left = stories.stream().mapToInt(List::size).sum();
        while (left > 0) {
            int s = random.nextInt(stories.size());
            if (next[s] < stories.get(s).size()) {
                merged.add(stories.get(s).get(next[s]++));
                left--;
            }
        }
        return merged;
    }

    private void insert(List<ScheduleEntry> entries, ScheduleEntry entry) {
        entries.add(random.nextInt(entries.size() + 1), entry);
    }
}
