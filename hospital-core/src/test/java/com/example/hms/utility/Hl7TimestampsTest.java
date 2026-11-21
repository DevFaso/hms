package com.example.hms.utility;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbound HL7 timestamps are explicit UTC instants (owner: the message said
 * 15:30 while the screen said 10:30); inbound accepts both forms.
 */
class Hl7TimestampsTest {

    @Test
    void formatsAStoredUtcWallTimeWithAnExplicitOffset() {
        assertThat(Hl7Timestamps.formatUtc(LocalDateTime.of(2026, 11, 19, 15, 30, 5)))
            .isEqualTo("20261119153005+0000");
        assertThat(Hl7Timestamps.formatUtc(null)).isNull();
    }

    @Test
    void nowCarriesTheUtcOffset() {
        assertThat(Hl7Timestamps.nowUtc()).matches("\\d{14}\\+0000");
    }

    @Test
    void readsAValueWithoutAnOffsetAsGiven() {
        assertThat(Hl7Timestamps.parse("20261119153000")).isEqualTo(LocalDateTime.of(2026, 11, 19, 15, 30));
        assertThat(Hl7Timestamps.parse("202611191530")).isEqualTo(LocalDateTime.of(2026, 11, 19, 15, 30));
        assertThat(Hl7Timestamps.parse("20261119")).isEqualTo(LocalDateTime.of(2026, 11, 19, 0, 0));
    }

    @Test
    void convertsAValueWithAnOffsetToUtc() {
        assertThat(Hl7Timestamps.parse("20261119153000+0000")).isEqualTo(LocalDateTime.of(2026, 11, 19, 15, 30));
        assertThat(Hl7Timestamps.parse("20261119153000-0500")).isEqualTo(LocalDateTime.of(2026, 11, 19, 20, 30));
        assertThat(Hl7Timestamps.parse("20261119003000+0100")).isEqualTo(LocalDateTime.of(2026, 11, 18, 23, 30));
        assertThat(Hl7Timestamps.parse("20261119153000.1234+0000")).isEqualTo(LocalDateTime.of(2026, 11, 19, 15, 30));
    }

    @Test
    void roundTripsItsOwnOutput() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        assertThat(Hl7Timestamps.parse(Hl7Timestamps.formatUtc(t))).isEqualTo(t);
    }

    @Test
    void refusesWhatIsNotATimestamp() {
        assertThat(Hl7Timestamps.parse(null)).isNull();
        assertThat(Hl7Timestamps.parse("")).isNull();
        assertThat(Hl7Timestamps.parse("yesterday")).isNull();
        assertThat(Hl7Timestamps.parse("20261340")).isNull();
    }
}
