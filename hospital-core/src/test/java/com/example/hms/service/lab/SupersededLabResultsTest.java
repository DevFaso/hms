package com.example.hms.service.lab;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
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

    private static LabResult row(LabOrder order, String testCode, String obx11,
                                 LocalDateTime observedAt, boolean released) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setTestCode(testCode);
        result.setSourceSendingApplication(SENDER);
        result.setObservationResultStatus(obx11);
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
    @DisplayName("the preliminary is superseded by its final")
    void theFinalSupersedesItsPreliminary() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "P", EARLIER, false);
        LabResult finalResult = row(order, "HGB", "F", LATER, true);

        assertThat(superseded(preliminary, finalResult)).containsExactly(preliminary.getId());
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
        LabResult finalResult = row(order, "HGB", "F", LATER, true);
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
        LabResult pendingCorrection = row(order, "K", "C", LATER, false);

        assertThat(superseded(releasedPreliminary, pendingCorrection)).isEmpty();
    }

    @Test
    @DisplayName("a corrected result is not a preliminary, so a later row never hides it")
    void aCorrectionIsNeverHidden() {
        LabOrder order = order();
        LabResult corrected = row(order, "K", "C", EARLIER, false);
        LabResult later = row(order, "K", "F", LATER, false);

        assertThat(superseded(corrected, later)).isEmpty();
    }

    @Test
    @DisplayName("a row the analyzer never described is never hidden — a hand-entered result is nobody's preliminary")
    void aRowWithoutAnAnalyzerStatusIsNeverHidden() {
        LabOrder order = order();
        LabResult handEntered = row(order, "HGB", null, EARLIER, false);
        LabResult finalResult = row(order, "HGB", "F", LATER, true);

        assertThat(superseded(handEntered, finalResult)).isEmpty();
    }

    @Test
    @DisplayName("a preliminary with no later row stands: an abnormal final that could not auto-release is still shown")
    void aPreliminaryWithNothingNewerIsKept() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "P", EARLIER, false);
        LabResult abnormalFinal = row(order, "HGB", "F", LATER, false);

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
        LabResult otherAnalyzer = row(order, "HGB", "F", LATER, true);
        otherAnalyzer.setSourceSendingApplication("MINDRAY");

        assertThat(superseded(preliminary, otherAnalyzer)).isEmpty();
    }

    @Test
    @DisplayName("a different analyte, and a different order, are left alone")
    void differentAnalyteOrOrderIsUntouched() {
        LabOrder order = order();
        LabResult pendingPlatelets = row(order, "PLT", "P", EARLIER, false);
        LabResult releasedHaemoglobin = row(order, "HGB", "F", LATER, true);
        assertThat(superseded(pendingPlatelets, releasedHaemoglobin)).isEmpty();

        LabResult onItsOwnOrder = row(order(), "HGB", "P", EARLIER, false);
        LabResult elsewhere = row(order(), "HGB", "F", LATER, true);
        assertThat(superseded(onItsOwnOrder, elsewhere)).isEmpty();
    }

    @Test
    @DisplayName("an earlier row never supersedes a later one, whichever order they are iterated in")
    void onlyANewerRowSupersedes() {
        LabOrder order = order();
        LabResult latePreliminary = row(order, "HGB", "P", LATER, false);
        LabResult earlyFinal = row(order, "HGB", "F", EARLIER, true);

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
        LabResult finalResult = row(order, "HGB", "F", LATER, true);

        assertThat(SupersededLabResults.isAnalyzerPreliminary(preliminary)).isTrue();
        assertThat(SupersededLabResults.isAnalyzerPreliminary(finalResult)).isFalse();
        assertThat(superseded(preliminary, finalResult)).containsExactly(preliminary.getId());
    }

    @Test
    @DisplayName("no rows, one row: nothing is superseded")
    void trivialInputs() {
        assertThat(SupersededLabResults.supersededRowIds(List.of(), List.of())).isEmpty();
        assertThat(superseded(row(order(), "HGB", "P", EARLIER, false))).isEmpty();
    }
}
