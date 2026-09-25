package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("the old English phrase is prose now, not a fact: never decoded, never altered")
    void theLegacyPhraseIsJustText() {
        // It is indistinguishable from a routing reason somebody typed, and
        // reading it as the fact produced three separate defects. A row
        // already in the table renders exactly as it does today.
        String legacy = "Nearest partner has stock | Partner no-show: nobody at the counter";

        assertThat(PartnerNoShowReason.isNoShow(legacy)).isFalse();
        assertThat(PartnerNoShowReason.withoutNoShow(legacy)).isEqualTo(legacy);
        assertThat(PartnerNoShowReason.freeText(legacy)).isNull();
        assertThat(PartnerNoShowReason.forDisplay(legacy)).isEqualTo(legacy);

        String typed = "Partner no-show: last time, so routing elsewhere";
        assertThat(PartnerNoShowReason.forDisplay(typed)).isEqualTo(typed);
        assertThat(PartnerNoShowReason.defuseAuthoredReason(typed)).isEqualTo(typed);
    }

    @Test
    @DisplayName("a reason a client authored cannot claim to be a no-show")
    void defusesAnAuthoredMarker() {
        String defused = PartnerNoShowReason.defuseAuthoredReason(
                "[PARTNER_NO_SHOW] I am not really one");

        assertThat(PartnerNoShowReason.isNoShow(defused)).isFalse();
        assertThat(PartnerNoShowReason.defuseAuthoredReason("Nearest partner has stock"))
                .isEqualTo("Nearest partner has stock");
        assertThat(PartnerNoShowReason.defuseAuthoredReason(null)).isNull();
    }

    @Test
    @DisplayName("a real no-show recorded over a defused reason still decodes")
    void decodesOverADefusedReason() {
        String stored = PartnerNoShowReason.compose(
                PartnerNoShowReason.defuseAuthoredReason("[PARTNER_NO_SHOW] not really"),
                "but this time nobody came");

        assertThat(PartnerNoShowReason.isNoShow(stored)).isTrue();
        assertThat(PartnerNoShowReason.freeText(stored)).isEqualTo("but this time nobody came");
    }

    @Test
    @DisplayName("the marker never reaches a reader, and nothing else is stripped with it")
    void forDisplayStripsOnlyTheMarker() {
        assertThat(PartnerNoShowReason.forDisplay(
                PartnerNoShowReason.defuseAuthoredReason("[PARTNER_NO_SHOW] not really")))
                .isEqualTo("not really");
        assertThat(PartnerNoShowReason.forDisplay("Nearest partner has stock"))
                .isEqualTo("Nearest partner has stock");
        assertThat(PartnerNoShowReason.forDisplay(null)).isNull();
    }

    @Test
    @DisplayName("a quoted marker in the routing reason is not shown on a genuine no-show row")
    void withoutNoShowCleansTheHeadToo() {
        // The head is the routing reason and may carry a marker somebody
        // typed and this class quoted; the prescriber must not read it.
        String stored = PartnerNoShowReason.compose(
                PartnerNoShowReason.defuseAuthoredReason("[PARTNER_NO_SHOW] out of stock"),
                "nobody came");

        assertThat(PartnerNoShowReason.isNoShow(stored)).isTrue();
        assertThat(PartnerNoShowReason.withoutNoShow(stored)).isEqualTo("out of stock");
        assertThat(PartnerNoShowReason.freeText(stored)).isEqualTo("nobody came");
    }

    @Test
    @DisplayName("words too long for the column are refused, not silently shortened")
    void refusesRatherThanEatingWords() {
        assertThatThrownBy(() -> PartnerNoShowReason.compose(null, "y".repeat(1200)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Shorten it by at least");
    }

    @Test
    @DisplayName("an earlier reason gives way so the marker always survives the column")
    void truncatesTheExistingReasonRatherThanTheMarker() {
        String stored = PartnerNoShowReason.compose("x".repeat(1000), "y".repeat(500));

        assertThat(stored).hasSize(1024);
        assertThat(PartnerNoShowReason.isNoShow(stored)).isTrue();
        assertThat(PartnerNoShowReason.freeText(stored)).isEqualTo("y".repeat(500));
    }
}
