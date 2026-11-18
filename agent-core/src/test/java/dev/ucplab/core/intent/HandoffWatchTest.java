package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The follow-up's own value rules (7.6, 10 V10, I8): its schedule, its shape, and the store's order. */
class HandoffWatchTest {

    private static final Instant T = Instant.parse("2026-11-18T12:00:00Z");

    // Covers: 7.6, 10 (a follow-up has a window and a next read together, or neither; the store's order may stay alone)
    @Test
    void aWindowAndANextRead_goTogether() {
        assertThatThrownBy(() -> new HandoffWatch(Optional.of(T), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HandoffWatch(Optional.empty(), Optional.of(T), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new HandoffWatch(Optional.empty(), Optional.empty(),
                Optional.of(new StoreOrder("gid://shopify/Order/42", Optional.empty()))).watching()).isFalse();
    }

    // Covers: 7.6 (the schedule from the handoff: 1, 2, 4, 8, 16, 30 minutes, then every 30)
    @Test
    void onTime_theNextReadIsTheSchedules() {
        HandoffWatch w = HandoffWatch.opened(T);
        assertThat(w.until()).hasValue(T.plus(Duration.ofHours(24)));
        assertThat(w.nextReadAt()).hasValue(T.plusSeconds(60));
        assertThat(w.nextRead(1, T.plusSeconds(60)).nextReadAt()).hasValue(T.plus(Duration.ofMinutes(2)));
        assertThat(w.nextRead(5, T.plus(Duration.ofMinutes(16))).nextReadAt()).hasValue(T.plus(Duration.ofMinutes(30)));
        assertThat(w.nextRead(6, T.plus(Duration.ofMinutes(30))).nextReadAt()).hasValue(T.plus(Duration.ofMinutes(60)));
    }

    // Covers: 7.6 (a late read does not bunch the next ones: when the scheduled time has passed, the next read is the
    // schedule's gap from now; and never later than the window's end)
    @Test
    void late_theNextReadIsTheGapFromNow_andTheWindowsEndBoundsIt() {
        HandoffWatch w = HandoffWatch.opened(T);
        Instant late = T.plus(Duration.ofMinutes(10));
        assertThat(w.nextRead(1, late).nextReadAt()).as("the gap after the first read is 1 min").hasValue(late.plusSeconds(60));
        assertThat(w.nextRead(0, late).nextReadAt()).as("the first read's gap").hasValue(late.plusSeconds(60));
        Instant nearEnd = T.plus(Duration.ofHours(24)).minusSeconds(60);
        assertThat(w.nextRead(60, nearEnd).nextReadAt()).hasValue(T.plus(Duration.ofHours(24)));
    }

    // Covers: 7.6 (an ended follow-up keeps the store's order and nothing else)
    @Test
    void ended_keepsOnlyTheStoresOrder() {
        StoreOrder order = new StoreOrder("gid://shopify/Order/42", Optional.of("LBLFAKE01"));
        HandoffWatch ended = HandoffWatch.opened(T).ended(Optional.of(order));
        assertThat(ended).isEqualTo(new HandoffWatch(Optional.empty(), Optional.empty(), Optional.of(order)));
    }

    // Covers: I8, VERIFY 11 (a store order has an id; its toString never shows the label, the buyer's confirmation code)
    @Test
    void aStoreOrder_hasAnId_andItsToStringHidesTheLabel() {
        assertThatThrownBy(() -> new StoreOrder(" ", Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new StoreOrder("gid://shopify/Order/42", Optional.of("LBLFAKE01")))
                .hasToString("StoreOrder[id=gid://shopify/Order/42, label=<present>]");
        assertThat(new StoreOrder("gid://shopify/Order/42", Optional.empty()))
                .hasToString("StoreOrder[id=gid://shopify/Order/42, label=<none>]");
    }
}
