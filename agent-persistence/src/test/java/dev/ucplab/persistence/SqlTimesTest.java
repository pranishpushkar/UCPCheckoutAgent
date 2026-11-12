package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlTimesTest {

    // Covers: section 10 (one fixed width: nine fractional digits and a Z, even for whole seconds)
    @Test
    void format_alwaysUsesNineFractionalDigits() {
        assertThat(SqlTimes.format(Instant.parse("2026-01-01T00:00:00Z"))).isEqualTo("2026-01-01T00:00:00.000000000Z");
        assertThat(SqlTimes.format(Instant.parse("2026-01-01T00:00:00.25Z"))).isEqualTo("2026-01-01T00:00:00.250000000Z");
        assertThat(SqlTimes.format(Instant.parse("2026-01-01T00:00:00.123456789Z")))
                .isEqualTo("2026-01-01T00:00:00.123456789Z");
    }

    // Covers: section 10 (text order is time order, which Instant.toString does not give: ":00Z" sorts after ":00.250Z")
    @Test
    void format_sortsAsTextInTimeOrder() {
        List<Instant> inTimeOrder = List.of(
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00.000000001Z"),
                Instant.parse("2026-01-01T00:00:00.25Z"), Instant.parse("2026-01-01T00:00:01Z"),
                Instant.parse("2026-12-31T23:59:59.999999999Z"));

        assertThat(Instant.parse("2026-01-01T00:00:00Z").toString())
                .isGreaterThan(Instant.parse("2026-01-01T00:00:00.25Z").toString());
        assertThat(inTimeOrder.stream().map(SqlTimes::format).sorted().toList())
                .isEqualTo(inTimeOrder.stream().map(SqlTimes::format).toList());
    }

    // Covers: section 10 (a round trip keeps every nanosecond)
    @Test
    void parse_returnsTheInstantFormatted() {
        Instant t = Instant.parse("2026-03-04T05:06:07.000000001Z");

        assertThat(SqlTimes.parse(SqlTimes.format(t))).isEqualTo(t);
    }

    // Covers: section 10 (text that is not in the fixed width is refused, not read leniently)
    @Test
    void parse_refusesOtherWidthsAndGarbage() {
        assertThatThrownBy(() -> SqlTimes.parse("2026-01-01T00:00:00Z")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlTimes.parse("2026-01-01T00:00:00.250Z")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlTimes.parse("2026-13-01T00:00:00.000000000Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: section 10 (an instant whose text would be wider is refused rather than stored with another width)
    @Test
    void format_refusesYearsBeyondFourDigits() {
        assertThatThrownBy(() -> SqlTimes.format(Instant.parse("+10000-01-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
