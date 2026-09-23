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
 * The rule both read paths share. An analyzer's preliminary and its final are
 * two stored rows on purpose — the ingest keeps both — so the pair is resolved
 * on the way out, and these cases pin exactly which row that hides.
 *
 * <p>The first version keyed on (order, analyte) alone and let a RELEASED row
 * supersede an unreleased one. Two of these cases are the patient-safety bugs
 * that followed: a timed series collapsed into one observation, and a
 * correction issued after a release disappeared behind the value it corrected.
 */
class SupersededLabResultsTest {

    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 9, 20, 8, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2026, 9, 20, 9, 0);

    private static LabOrder order() {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        return order;
    }

    private static LabResult row(LabOrder order, String testCode, String setId,
                                 LocalDateTime observedAt, boolean released) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setTestCode(testCode);
        result.setSourceObservationSetId(setId);
        result.setResultDate(observedAt);
        result.setReleased(released);
        return result;
    }

    @Test
    @DisplayName("the preliminary is superseded by its final, even when both carry the same observation time")
    void theFinalSupersedesItsPreliminary() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "1", EARLIER, false);
        preliminary.setCreatedAt(EARLIER);
        LabResult finalResult = row(order, "HGB", "1", EARLIER, true);
        finalResult.setCreatedAt(LATER);

        assertThat(SupersededLabResults.superseded(List.of(preliminary, finalResult)))
            .containsExactly(preliminary);
    }

    @Test
    @DisplayName("PATIENT SAFETY: a timed series of one analyte is not one observation reported twice")
    void aTimedSeriesIsNotCollapsed() {
        // Glucose at 0 and 30 minutes, in one order. The first was normal and
        // auto-released; the second is abnormal and waiting on a person. Keyed
        // without the set id, the abnormal draw vanished from the patient's
        // view and order completion closed the order over it.
        LabOrder order = order();
        LabResult firstDraw = row(order, "GLU", "1", EARLIER, true);
        LabResult secondDraw = row(order, "GLU", "2", LATER, false);

        assertThat(SupersededLabResults.superseded(List.of(firstDraw, secondDraw))).isEmpty();
    }

    @Test
    @DisplayName("PATIENT SAFETY: a correction arriving after a release is not hidden by the value it corrects")
    void aCorrectionIsNeverSupersededByTheReleasedRowItReplaces() {
        LabOrder order = order();
        LabResult released = row(order, "K", "1", EARLIER, true);
        LabResult correction = row(order, "K", "1", LATER, false);

        // Only the earlier row is left behind; the correction survives.
        assertThat(SupersededLabResults.superseded(List.of(released, correction)))
            .containsExactly(released);
    }

    @Test
    @DisplayName("the pairing does not depend on release: an abnormal final that could not auto-release still supersedes")
    void anUnreleasedFinalStillSupersedesItsPreliminary() {
        // An abnormal final is not auto-releasable, so neither row is released.
        // Keyed on release, nothing was superseded and the portal listed the
        // same test as pending twice.
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", "1", EARLIER, false);
        LabResult abnormalFinal = row(order, "HGB", "1", LATER, false);

        assertThat(SupersededLabResults.superseded(List.of(preliminary, abnormalFinal)))
            .containsExactly(preliminary);
    }

    @Test
    @DisplayName("order of arrival does not matter — the latest wins either way round")
    void theLatestWinsRegardlessOfIterationOrder() {
        LabOrder order = order();
        LabResult earlier = row(order, "HGB", "1", EARLIER, false);
        LabResult later = row(order, "HGB", "1", LATER, false);

        assertThat(SupersededLabResults.superseded(List.of(later, earlier))).containsExactly(earlier);
        assertThat(SupersededLabResults.superseded(List.of(earlier, later))).containsExactly(earlier);
    }

    @Test
    @DisplayName("a different analyte on the same order is untouched — a panel keeps its pending members")
    void anotherAnalyteIsNotSuperseded() {
        LabOrder order = order();
        LabResult pendingPlatelets = row(order, "PLT", "1", LATER, false);
        LabResult releasedHaemoglobin = row(order, "HGB", "1", LATER, true);

        assertThat(SupersededLabResults.superseded(List.of(pendingPlatelets, releasedHaemoglobin))).isEmpty();
    }

    @Test
    @DisplayName("the same analyte on a DIFFERENT order is untouched")
    void anotherOrderIsNotSuperseded() {
        LabResult onItsOwnOrder = row(order(), "HGB", "1", EARLIER, false);
        LabResult elsewhere = row(order(), "HGB", "1", LATER, true);

        assertThat(SupersededLabResults.superseded(List.of(onItsOwnOrder, elsewhere))).isEmpty();
    }

    @Test
    @DisplayName("a row missing any part of the key names no observation — a hand-entered result stands alone")
    void anUnkeyedRowIsNeverSuperseded() {
        LabOrder order = order();
        LabResult noTestCode = row(order, null, "1", EARLIER, false);
        LabResult noSetId = row(order, "HGB", null, EARLIER, false);
        LabResult blankSetId = row(order, "HGB", "  ", EARLIER, false);
        LabResult released = row(order, "HGB", "1", LATER, true);

        assertThat(SupersededLabResults.superseded(List.of(noTestCode, noSetId, blankSetId, released)))
            .isEmpty();
    }

    @Test
    @DisplayName("with nothing to compare, the released row is preferred over the pending one")
    void releaseBreaksAnOtherwiseExactTie() {
        LabOrder order = order();
        LabResult pending = row(order, "HGB", "1", EARLIER, false);
        LabResult released = row(order, "HGB", "1", EARLIER, true);

        assertThat(SupersededLabResults.superseded(List.of(pending, released))).containsExactly(pending);
    }

    @Test
    @DisplayName("no rows, one row: nothing is superseded")
    void trivialInputs() {
        assertThat(SupersededLabResults.superseded(List.of())).isEmpty();
        assertThat(SupersededLabResults.superseded(List.of(row(order(), "HGB", "1", EARLIER, false)))).isEmpty();
    }
}
