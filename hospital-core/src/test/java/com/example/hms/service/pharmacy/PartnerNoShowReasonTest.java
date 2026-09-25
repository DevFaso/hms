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
    @DisplayName("the column is 1024 characters and the EXISTING reason gives way, never the marker")
    void truncatesTheExistingReasonRatherThanTheMarker() {
        String stored = PartnerNoShowReason.compose("x".repeat(1000), "y".repeat(500));

        // Truncating the tail would have sliced through the marker and the
        // no-show would have vanished from the API on a CANCELLED decision.
        assertThat(stored).hasSize(1024);
        assertThat(PartnerNoShowReason.isNoShow(stored)).isTrue();
        assertThat(PartnerNoShowReason.freeText(stored)).isEqualTo("y".repeat(500));
    }

    @Test
    @DisplayName("words too long for the column on their own keep the marker and lose the tail")
    void truncatesTheWordsOnlyWhenTheyAloneOverflow() {
        String stored = PartnerNoShowReason.compose("Nearest partner has stock", "z".repeat(1200));

        assertThat(stored).hasSize(1024).startsWith("[PARTNER_NO_SHOW] ");
        assertThat(PartnerNoShowReason.isNoShow(stored)).isTrue();
    }

    @Test
    @DisplayName("a routing reason that merely mentions a no-show is not one")
    void onlyASegmentStartCounts() {
        // Free text the pharmacist typed at route-to-partner time. Treating it
        // as the fact would put a translated "the partner never delivered" on
        // a decision nobody recorded one for, and strip their sentence out.
        String typed = "Partner no-show last month, so routing elsewhere";

        assertThat(PartnerNoShowReason.isNoShow(typed)).isFalse();
        assertThat(PartnerNoShowReason.withoutNoShow(typed)).isEqualTo(typed);

        String midSentence = "Rerouted because Partner no-show: was recorded before";
        assertThat(PartnerNoShowReason.isNoShow(midSentence)).isFalse();
        assertThat(PartnerNoShowReason.withoutNoShow(midSentence)).isEqualTo(midSentence);
    }

    @Test
    @DisplayName("a reason a client authored cannot claim to be a no-show")
    void defusesAnAuthoredMarker() {
        // Index 0 is where an authored reason starts AND where a real no-show
        // segment sits when the decision had no earlier reason, so the two
        // cannot be told apart afterwards. They are kept apart beforehand.
        String defused = PartnerNoShowReason.defuseAuthoredReason(
                "[PARTNER_NO_SHOW] I am not really one");

        assertThat(PartnerNoShowReason.isNoShow(defused)).isFalse();
        assertThat(PartnerNoShowReason.withoutNoShow(defused)).isEqualTo(defused);

        String legacyShaped = PartnerNoShowReason.defuseAuthoredReason(
                "Partner no-show: last time, so routing elsewhere");
        assertThat(PartnerNoShowReason.isNoShow(legacyShaped)).isFalse();

        assertThat(PartnerNoShowReason.defuseAuthoredReason("Nearest partner has stock"))
                .isEqualTo("Nearest partner has stock");
        assertThat(PartnerNoShowReason.defuseAuthoredReason(null)).isNull();
    }

    @Test
    @DisplayName("defusing a full-length reason does not overflow the column")
    void defusingStaysWithinTheColumn() {
        // The request is validated at the column's own 1024, and each defused
        // token grows by two characters — an insert away from a 500.
        String full = "Partner no-show: " + "x".repeat(1024 - "Partner no-show: ".length());

        assertThat(full).hasSize(1024);
        assertThat(PartnerNoShowReason.defuseAuthoredReason(full)).hasSize(1024);
        assertThat(PartnerNoShowReason.isNoShow(
                PartnerNoShowReason.defuseAuthoredReason(full))).isFalse();
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
    @DisplayName("but a real no-show after such a reason still decodes")
    void stillDecodesAfterAReasonThatMentionsIt() {
        String stored = PartnerNoShowReason.compose(
                "Partner no-show last month, so routing elsewhere", "again, nobody came");

        assertThat(PartnerNoShowReason.isNoShow(stored)).isTrue();
        assertThat(PartnerNoShowReason.freeText(stored)).isEqualTo("again, nobody came");
        assertThat(PartnerNoShowReason.withoutNoShow(stored))
                .isEqualTo("Partner no-show last month, so routing elsewhere");
    }
}
