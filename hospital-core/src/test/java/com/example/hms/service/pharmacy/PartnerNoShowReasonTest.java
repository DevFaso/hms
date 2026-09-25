package com.example.hms.service.pharmacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The no-show fact is a code, not a sentence: a sentence composed in English
 * and stored in the reason column reached French and Spanish prescribers in
 * English, and stored text cannot be translated at render time.
 */
class PartnerNoShowReasonTest {

    @Test
    @DisplayName("composes a marker, not English prose, and keeps the words the pharmacist typed")
    void composesAMarker() {
        String stored = PartnerNoShowReason.compose("Nearest partner has stock", "nobody at the counter");

        assertThat(stored).doesNotContain("Partner no-show");
        assertThat(PartnerNoShowReason.isNoShow(stored)).isTrue();
        assertThat(PartnerNoShowReason.withoutNoShow(stored)).isEqualTo("Nearest partner has stock");
        assertThat(PartnerNoShowReason.freeText(stored)).isEqualTo("nobody at the counter");
    }

    @Test
    @DisplayName("a decision recorded with no earlier reason keeps only the pharmacist's words")
    void composesWithoutAnExistingReason() {
        String stored = PartnerNoShowReason.compose(null, "never delivered");

        assertThat(PartnerNoShowReason.withoutNoShow(stored)).isNull();
        assertThat(PartnerNoShowReason.freeText(stored)).isEqualTo("never delivered");
    }

    @Test
    @DisplayName("rows written before the marker decode the same way — no migration needed")
    void decodesTheLegacyLiteral() {
        String legacy = "Nearest partner has stock | Partner no-show: nobody at the counter";

        assertThat(PartnerNoShowReason.isNoShow(legacy)).isTrue();
        assertThat(PartnerNoShowReason.withoutNoShow(legacy)).isEqualTo("Nearest partner has stock");
        assertThat(PartnerNoShowReason.freeText(legacy)).isEqualTo("nobody at the counter");
    }

    @Test
    @DisplayName("the pharmacist's own words may contain the separator")
    void freeTextMayContainTheSeparator() {
        String stored = PartnerNoShowReason.compose("Out of stock", "waited Monday | and Tuesday");

        assertThat(PartnerNoShowReason.withoutNoShow(stored)).isEqualTo("Out of stock");
        assertThat(PartnerNoShowReason.freeText(stored)).isEqualTo("waited Monday | and Tuesday");
    }

    @Test
    @DisplayName("an ordinary routing reason is untouched and is not a no-show")
    void leavesAnOrdinaryReasonAlone() {
        assertThat(PartnerNoShowReason.isNoShow("Medication out of stock")).isFalse();
        assertThat(PartnerNoShowReason.withoutNoShow("Medication out of stock"))
                .isEqualTo("Medication out of stock");
        assertThat(PartnerNoShowReason.freeText("Medication out of stock")).isNull();
        assertThat(PartnerNoShowReason.isNoShow(null)).isFalse();
        assertThat(PartnerNoShowReason.withoutNoShow(null)).isNull();
    }

    @Test
    @DisplayName("the column is 1024 characters and a long reason is truncated, not rejected")
    void truncatesToTheColumnWidth() {
        String stored = PartnerNoShowReason.compose("x".repeat(1000), "y".repeat(500));

        assertThat(stored).hasSize(1024);
    }
}
