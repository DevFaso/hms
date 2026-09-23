package com.example.hms.service.lab;

import com.example.hms.enums.ActorType;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule both read paths share.
 *
 * <p>Which row of a preliminary/final pair may be hidden was inferred three
 * times — from the observation set id, from release state, from recency — and
 * each attempt had a hole. Several of these cases ARE those holes, kept as
 * cases so the next person can see why the rule asks the analyzer (OBX-11)
 * rather than guessing.
 */
class SupersededLabResultsTest {

    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 9, 20, 8, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2026, 9, 20, 9, 0);
    private static final String SENDER = "SYSMEX";

    private static LabOrder order() {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        return order;
    }

    /** A row as the MLLP ingest writes it: SYSTEM actor, a sender, and whatever OBX-11 said. */
    private static LabResult row(LabOrder order, String testCode, String obx11,
                                 LocalDateTime observedAt, boolean released) {
        LabResult result = handEntered(order, testCode, observedAt, released);
        result.setActorType(ActorType.SYSTEM);
        result.setSourceSendingApplication(SENDER);
        result.setObservationResultStatus(obx11);
        return result;
    }

    /** A row as a person writes it: no sender, no status, no analyzer marks at all. */
    private static LabResult handEntered(LabOrder order, String testCode,
                                         LocalDateTime observedAt, boolean released) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setTestCode(testCode);
        result.setResultDate(observedAt);
        result.setCreatedAt(observedAt);
        result.setReleased(released);
        return result;
    }

    private static List<UUID> superseded(LabResult... rows) {
        List<LabResult> all = List.of(rows);
        return List.copyOf(SupersededLabResults.supersededRowIds(all, all));
    }

    @Test
    @DisplayName("the preliminary is superseded by its final — one draw, one observation time, two messages")
    void theFinalSupersedesItsPreliminary() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "P", EARLIER, false);
        LabResult finalResult = row(order, "HGB", "F", EARLIER, true);
        finalResult.setCreatedAt(LATER);

        assertThat(superseded(preliminary, finalResult)).containsExactly(preliminary.getId());
    }

    @Test
    @DisplayName("PATIENT SAFETY: a timed series is never collapsed, even when a draw is marked preliminary")
    void aTimedSeriesSurvivesAPreliminaryStatus() {
        // Glucose at 0 and 30 minutes. Without the observation time in the key
        // this collapsed the moment any draw carried a preliminary status, and
        // the earlier draw — which may be the abnormal one — disappeared.
        LabOrder order = order();
        LabResult firstDraw = row(order, "GLU", "P", EARLIER, false);
        LabResult secondDraw = row(order, "GLU", "F", LATER, true);

        assertThat(superseded(firstDraw, secondDraw)).isEmpty();
    }

    @Test
    @DisplayName("a final re-sending a subset of the panel still supersedes — the set id is not part of the key")
    void aSubsetResendStillSupersedes() {
        // The set id is unique only WITHIN a message and falls back to
        // positional numbering, so the same analyte can arrive as OBX-3 in the
        // preliminary and OBX-1 in the final. Keyed on it, nothing superseded
        // anything and the patient kept a permanent "pending".
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "P", EARLIER, false);
        preliminary.setSourceObservationSetId("3");
        LabResult finalResult = row(order, "HGB", "F", EARLIER, true);
        finalResult.setCreatedAt(LATER);
        finalResult.setSourceObservationSetId("1");

        assertThat(superseded(preliminary, finalResult)).containsExactly(preliminary.getId());
    }

    @Test
    @DisplayName("PATIENT SAFETY: a timed series is not collapsed — no draw is a preliminary")
    void aTimedSeriesIsNotCollapsed() {
        // One message per draw gives every draw set id 1, so the old key put a
        // whole series on one key. With an ABNORMAL earlier draw and a normal
        // later one that auto-released, the abnormal row was hidden and dropped
        // from order completion. Neither draw is marked preliminary, so
        // neither can be hidden now, whichever way round they fall.
        LabOrder order = order();
        LabResult abnormalFirstDraw = row(order, "GLU", "F", EARLIER, false);
        abnormalFirstDraw.setSourceObservationSetId("1");
        LabResult normalSecondDraw = row(order, "GLU", "F", LATER, true);
        normalSecondDraw.setSourceObservationSetId("1");

        assertThat(superseded(abnormalFirstDraw, normalSecondDraw)).isEmpty();
    }

    @Test
    @DisplayName("PATIENT SAFETY: a released value is never taken away and replaced with nothing")
    void aReleasedRowIsNeverHiddenBehindAnUnreleasedOne() {
        LabOrder order = order();
        LabResult releasedPreliminary = row(order, "K", "P", EARLIER, true);
        LabResult pendingCorrection = row(order, "K", "C", EARLIER, false);
        pendingCorrection.setCreatedAt(LATER);

        assertThat(superseded(releasedPreliminary, pendingCorrection)).isEmpty();
    }

    @Test
    @DisplayName("a corrected result is not a preliminary, so a later row never hides it")
    void aCorrectionIsNeverHidden() {
        LabOrder order = order();
        LabResult corrected = row(order, "K", "C", EARLIER, false);
        LabResult later = row(order, "K", "F", EARLIER, false);
        later.setCreatedAt(LATER);

        assertThat(superseded(corrected, later)).isEmpty();
    }

    @Test
    @DisplayName("a hand-entered result is judged by NEITHER rule, test code and all")
    void aHandEnteredRowIsNeverHidden() {
        // The mapper does copy the test code, so "no test code" was never what
        // protected these rows. What protects them is provenance: no analyzer
        // marks, so neither rule applies. Selecting the fallback on a missing
        // status instead handed a person's results a rule meant for pre-V164
        // analyzer data — and under it a released row could hide an unreleased
        // correction and close the order over it.
        LabOrder order = order();
        LabResult handEntered = handEntered(order, "HGB", EARLIER, false);
        LabResult releasedLater = handEntered(order, "HGB", LATER, true);

        assertThat(superseded(handEntered, releasedLater)).isEmpty();
    }

    @Test
    @DisplayName("a person's correction is never hidden behind the released value it corrects")
    void aHandEnteredCorrectionSurvives() {
        LabOrder order = order();
        LabResult released = handEntered(order, "K", EARLIER, true);
        LabResult correction = handEntered(order, "K", LATER, false);

        assertThat(superseded(released, correction)).isEmpty();
        assertThat(superseded(correction, released)).isEmpty();
    }

    @Test
    @DisplayName("only an analyzer row reaches the pre-V164 fallback")
    void theFallbackIsSelectedByProvenance() {
        LabOrder order = order();
        LabResult analyzerRow = handEntered(order, "HGB", EARLIER, false);
        analyzerRow.setActorType(ActorType.SYSTEM);
        LabResult released = handEntered(order, "HGB", LATER, true);
        released.setActorType(ActorType.SYSTEM);

        assertThat(SupersededLabResults.cameFromAnAnalyzer(analyzerRow)).isTrue();
        assertThat(superseded(analyzerRow, released)).containsExactly(analyzerRow.getId());
    }

    @Test
    @DisplayName("a preliminary with no later row stands: an abnormal final that could not auto-release is still shown")
    void aPreliminaryWithNothingNewerIsKept() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "P", EARLIER, false);
        LabResult abnormalFinal = row(order, "HGB", "F", EARLIER, false);
        abnormalFinal.setCreatedAt(LATER);

        // The final is unreleased (abnormal, so not auto-releasable) and still
        // supersedes: the pairing does not ask about release, only about which
        // row the analyzer replaced.
        assertThat(superseded(preliminary, abnormalFinal)).containsExactly(preliminary.getId());
    }

    @Test
    @DisplayName("another sending application never supersedes, even for the same analyte on the same order")
    void anotherSenderNeverSupersedes() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "P", EARLIER, false);
        LabResult otherAnalyzer = row(order, "HGB", "F", EARLIER, true);
        otherAnalyzer.setCreatedAt(LATER);
        otherAnalyzer.setSourceSendingApplication("MINDRAY");

        assertThat(superseded(preliminary, otherAnalyzer)).isEmpty();
    }

    @Test
    @DisplayName("a different analyte, and a different order, are left alone")
    void differentAnalyteOrOrderIsUntouched() {
        LabOrder order = order();
        LabResult pendingPlatelets = row(order, "PLT", "P", EARLIER, false);
        LabResult releasedHaemoglobin = row(order, "HGB", "F", EARLIER, true);
        assertThat(superseded(pendingPlatelets, releasedHaemoglobin)).isEmpty();

        LabResult onItsOwnOrder = row(order(), "HGB", "P", EARLIER, false);
        LabResult elsewhere = row(order(), "HGB", "F", EARLIER, true);
        assertThat(superseded(onItsOwnOrder, elsewhere)).isEmpty();
    }

    @Test
    @DisplayName("an earlier row never supersedes a later one, whichever order they are iterated in")
    void onlyANewerRowSupersedes() {
        LabOrder order = order();
        LabResult latePreliminary = row(order, "HGB", "P", EARLIER, false);
        latePreliminary.setCreatedAt(LATER);
        LabResult earlyFinal = row(order, "HGB", "F", EARLIER, true);
        earlyFinal.setCreatedAt(EARLIER);

        assertThat(superseded(latePreliminary, earlyFinal)).isEmpty();
        assertThat(superseded(earlyFinal, latePreliminary)).isEmpty();
    }

    @Test
    @DisplayName("two rows stamped identically resolve the same way every time, not by repository order")
    void anExactTieIsBrokenDeterministicallyByRowId() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "P", EARLIER, false);
        LabResult twin = row(order, "HGB", "F", EARLIER, false);
        // Same observation time and same write time: only the id separates them.
        // Same observation time and same write time: only the id separates them.
        boolean twinIsGreater = twin.getId().compareTo(preliminary.getId()) > 0;

        assertThat(superseded(preliminary, twin))
            .isEqualTo(twinIsGreater ? List.of(preliminary.getId()) : List.of());
        // The answer does not depend on the order the rows arrived in.
        assertThat(superseded(twin, preliminary))
            .isEqualTo(twinIsGreater ? List.of(preliminary.getId()) : List.of());
    }

    @Test
    @DisplayName("the status is read case- and whitespace-insensitively, as the wire sends it")
    void theStatusIsNormalised() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", " p ", EARLIER, false);
        LabResult finalResult = row(order, "HGB", "F", EARLIER, true);
        finalResult.setCreatedAt(LATER);

        assertThat(SupersededLabResults.isAnalyzerPreliminary(preliminary)).isTrue();
        assertThat(SupersededLabResults.isAnalyzerPreliminary(finalResult)).isFalse();
        assertThat(superseded(preliminary, finalResult)).containsExactly(preliminary.getId());
    }

    // ── rows written before V164, which carry no status at all ──────────

    @Test
    @DisplayName("a pre-V164 pair is still resolved, by the older rule — otherwise this release regresses live data")
    void aRowWithoutAStatusFallsBackToTheOlderRule() {
        // V164 backfills nothing, so every analyzer row already in the
        // database has no status. Judged by the analyzer rule alone they would
        // be superseded by nothing, their orders would sit in RESULTED for
        // ever and the patient would keep a duplicate pending row — worse than
        // the behaviour that data has today.
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", null, EARLIER, false);
        LabResult releasedFinal = row(order, "HGB", null, LATER, true);

        assertThat(superseded(preliminary, releasedFinal)).containsExactly(preliminary.getId());
    }

    @Test
    @DisplayName("the older rule needs the later row RELEASED, and never hides a released row")
    void theOlderRuleKeepsItsGuards() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", null, EARLIER, false);
        LabResult unreleasedLater = row(order, "HGB", null, LATER, false);
        assertThat(superseded(preliminary, unreleasedLater)).isEmpty();

        LabResult releasedEarlier = row(order, "K", null, EARLIER, true);
        LabResult releasedLater = row(order, "K", null, LATER, true);
        assertThat(superseded(releasedEarlier, releasedLater))
            .as("a released row is never taken away")
            .isEmpty();
    }

    @Test
    @DisplayName("a row the analyzer DID describe is never judged by the older rule")
    void aDescribedRowNeverFallsBack() {
        // Final, not preliminary: the analyzer rule keeps it, and the older
        // rule — which would have hidden it behind the released row — does not
        // get a say.
        LabOrder order = order();
        LabResult analyzerFinal = row(order, "HGB", "F", EARLIER, false);
        LabResult releasedLater = row(order, "HGB", "F", LATER, true);

        assertThat(superseded(analyzerFinal, releasedLater)).isEmpty();
    }

    // ── the sender: application, or facility when there is no application ─

    @Test
    @DisplayName("a sender identified by facility alone still has its pairs resolved")
    void theFacilityStandsInForAnAbsentApplication() {
        // The ingest stores both trimmed-to-null, and the transmit guard was
        // widened for exactly this sender. Demanding the application here left
        // its pairs never collapsing and its orders never completing.
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "P", EARLIER, false);
        preliminary.setSourceSendingApplication(null);
        preliminary.setSourceSendingFacility("LAB_A");
        LabResult finalResult = row(order, "HGB", "F", EARLIER, true);
        finalResult.setCreatedAt(LATER);
        finalResult.setSourceSendingApplication(null);
        finalResult.setSourceSendingFacility("LAB_A");

        assertThat(superseded(preliminary, finalResult)).containsExactly(preliminary.getId());
    }

    @Test
    @DisplayName("two facilities are two senders")
    void adifferentFacilityNeverSupersedes() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "P", EARLIER, false);
        preliminary.setSourceSendingApplication(null);
        preliminary.setSourceSendingFacility("LAB_A");
        LabResult otherLab = row(order, "HGB", "F", EARLIER, true);
        otherLab.setCreatedAt(LATER);
        otherLab.setSourceSendingApplication(null);
        otherLab.setSourceSendingFacility("LAB_B");

        assertThat(superseded(preliminary, otherLab)).isEmpty();
    }

    @Test
    @DisplayName("the survivor named is the one nothing else replaces, not whichever matched first")
    void theReplacementIsTheRowThatSurvives() {
        // Preliminary, a second preliminary, then the final — all one draw. If
        // the first preliminary's replacement were "whichever matched first"
        // it could name the second preliminary, and a caller folding that
        // survivor into a page would put a superseded pending row in front of
        // the patient.
        LabOrder order = order();
        LabResult firstPreliminary = row(order, "HGB", "P", EARLIER, false);
        firstPreliminary.setCreatedAt(EARLIER);
        LabResult secondPreliminary = row(order, "HGB", "P", EARLIER, false);
        secondPreliminary.setCreatedAt(EARLIER.plusMinutes(1));
        LabResult finalResult = row(order, "HGB", "F", EARLIER, true);
        finalResult.setCreatedAt(LATER);

        List<LabResult> all = List.of(firstPreliminary, secondPreliminary, finalResult);
        Map<UUID, LabResult> replacements = SupersededLabResults.replacements(all, all);

        assertThat(replacements.keySet())
            .containsExactlyInAnyOrder(firstPreliminary.getId(), secondPreliminary.getId());
        assertThat(replacements.values())
            .as("both name the row that survives")
            .allMatch(winner -> winner.getId().equals(finalResult.getId()));
    }

    @Test
    @DisplayName("an analyzer that sends no observation time still has its pair resolved")
    void aPairWithNoAnalyzerObservationTimeStillResolves() {
        // The ingest gives such rows the ORDER's datetime rather than the
        // instant each message landed, so the two reports of one observation
        // share it. Substituting per-message left the pair unmatched for ever:
        // the order stuck in RESULTED and a duplicate pending row on the
        // patient's page, with the pre-V164 fallback unreachable because the
        // row does carry a status.
        LabOrder order = order();
        LocalDateTime orderedAt = EARLIER;
        LabResult preliminary = row(order, "HGB", "P", orderedAt, false);
        preliminary.setCreatedAt(orderedAt);
        LabResult finalResult = row(order, "HGB", "F", orderedAt, true);
        finalResult.setCreatedAt(LATER);

        assertThat(superseded(preliminary, finalResult)).containsExactly(preliminary.getId());
    }

    @Test
    @DisplayName("the pre-V164 fallback does NOT require the released row to be newer — that is develop's rule")
    void theFallbackHasNoRecencyTest() {
        // A legacy pair whose released final happens to carry the earlier
        // result date is still superseded: requiring recency here would
        // regress exactly the live data this path exists to protect.
        LabOrder order = order();
        LabResult pending = row(order, "HGB", null, LATER, false);
        LabResult releasedEarlier = row(order, "HGB", null, EARLIER, true);

        assertThat(superseded(pending, releasedEarlier)).containsExactly(pending.getId());
    }

    @Test
    @DisplayName("no rows, one row: nothing is superseded")
    void trivialInputs() {
        assertThat(SupersededLabResults.supersededRowIds(List.of(), List.of())).isEmpty();
        assertThat(superseded(row(order(), "HGB", "P", EARLIER, false))).isEmpty();
    }
}
