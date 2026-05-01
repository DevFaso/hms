package com.example.hms.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DataDomain")
class DataDomainTest {

    @Nested
    @DisplayName("parseCsv")
    class ParseCsv {
        @Test void blankReturnsEmpty() {
            assertThat(DataDomain.parseCsv(null)).isEmpty();
            assertThat(DataDomain.parseCsv("")).isEmpty();
            assertThat(DataDomain.parseCsv("   ")).isEmpty();
        }

        @Test void parsesSpaceAndCaseInsensitively() {
            Set<DataDomain> parsed = DataDomain.parseCsv(" prescriptions , LAB_results ,encounters ");
            assertThat(parsed).containsExactlyInAnyOrder(
                DataDomain.PRESCRIPTIONS, DataDomain.LAB_RESULTS, DataDomain.ENCOUNTERS);
        }

        @Test void unknownTokenThrows() {
            assertThatThrownBy(() -> DataDomain.parseCsv("PRESCRIPTIONS,UNKNOWN_DOMAIN"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("covers")
    class Covers {
        @Test void blankCsvCoversAllNonSensitive() {
            assertThat(DataDomain.covers(null, DataDomain.PRESCRIPTIONS)).isTrue();
            assertThat(DataDomain.covers("", DataDomain.LAB_RESULTS)).isTrue();
        }

        @Test void blankCsvDoesNotCoverSensitive() {
            for (DataDomain s : DataDomain.SENSITIVE) {
                assertThat(DataDomain.covers(null, s))
                    .as("blank csv must NOT cover sensitive domain %s", s)
                    .isFalse();
            }
        }

        @Test void explicitListedSensitiveCovers() {
            assertThat(DataDomain.covers("HIV_STATUS,PRESCRIPTIONS", DataDomain.HIV_STATUS)).isTrue();
            assertThat(DataDomain.covers("HIV_STATUS", DataDomain.MENTAL_HEALTH)).isFalse();
        }

        @Test void nonMatchingExplicitListReturnsFalse() {
            assertThat(DataDomain.covers("PRESCRIPTIONS", DataDomain.IMAGING)).isFalse();
        }

        @Test void nullRequestedNeverCovers() {
            assertThat(DataDomain.covers(null, null)).isFalse();
            assertThat(DataDomain.covers("PRESCRIPTIONS", null)).isFalse();
        }
    }

    @Test void sensitiveSetIsExactlyTheKnownFour() {
        assertThat(DataDomain.SENSITIVE).isEqualTo(EnumSet.of(
            DataDomain.MENTAL_HEALTH,
            DataDomain.HIV_STATUS,
            DataDomain.SUBSTANCE_USE,
            DataDomain.GENETICS
        ));
    }

    @Test void toCsvRoundTripsThroughParseCsv() {
        Set<DataDomain> set = EnumSet.of(DataDomain.PRESCRIPTIONS, DataDomain.LAB_RESULTS);
        String csv = DataDomain.toCsv(set);
        assertThat(DataDomain.parseCsv(csv)).isEqualTo(set);
    }
}
