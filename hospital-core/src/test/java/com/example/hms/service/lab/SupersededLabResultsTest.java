package com.example.hms.service.lab;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule both read paths share. An analyzer's preliminary and its final are
 * two stored rows on purpose — the ingest must keep both — so the pair is
 * resolved on the way out, and these cases pin exactly which row that hides.
 */
class SupersededLabResultsTest {

    private static LabOrder order() {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        return order;
    }

    private static LabResult row(LabOrder order, String testCode, boolean released) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setTestCode(testCode);
        result.setReleased(released);
        return result;
    }

    @Test
    @DisplayName("the unreleased row for an analyte that already has a released one is superseded")
    void preliminaryIsSupersededByItsFinal() {
        LabOrder order = order();
        LabResult preliminary = row(order, "HGB", false);
        LabResult released = row(order, "HGB", true);

        Set<SupersededLabResults.AnalyteKey> analytes =
            SupersededLabResults.releasedAnalytes(List.of(preliminary, released));

        assertThat(SupersededLabResults.isSupersededByRelease(preliminary, analytes)).isTrue();
        assertThat(SupersededLabResults.isSupersededByRelease(released, analytes)).isFalse();
    }

    @Test
    @DisplayName("a different analyte on the same order is untouched — a panel keeps its pending members")
    void anotherAnalyteIsNotSuperseded() {
        LabOrder order = order();
        LabResult pendingPlatelets = row(order, "PLT", false);
        LabResult releasedHaemoglobin = row(order, "HGB", true);

        Set<SupersededLabResults.AnalyteKey> analytes =
            SupersededLabResults.releasedAnalytes(List.of(pendingPlatelets, releasedHaemoglobin));

        assertThat(SupersededLabResults.isSupersededByRelease(pendingPlatelets, analytes)).isFalse();
    }

    @Test
    @DisplayName("the same analyte on a DIFFERENT order is untouched")
    void anotherOrderIsNotSuperseded() {
        LabResult pendingOnItsOwnOrder = row(order(), "HGB", false);
        LabResult releasedElsewhere = row(order(), "HGB", true);

        Set<SupersededLabResults.AnalyteKey> analytes =
            SupersededLabResults.releasedAnalytes(List.of(pendingOnItsOwnOrder, releasedElsewhere));

        assertThat(SupersededLabResults.isSupersededByRelease(pendingOnItsOwnOrder, analytes)).isFalse();
    }

    @Test
    @DisplayName("a row with no test code names no analyte — a hand-entered result is nobody's preliminary")
    void anUntypedRowIsNeverSuperseded() {
        LabOrder order = order();
        LabResult untyped = row(order, null, false);
        LabResult blankCoded = row(order, "  ", false);
        LabResult released = row(order, "HGB", true);

        Set<SupersededLabResults.AnalyteKey> analytes =
            SupersededLabResults.releasedAnalytes(List.of(untyped, blankCoded, released));

        assertThat(SupersededLabResults.isSupersededByRelease(untyped, analytes)).isFalse();
        assertThat(SupersededLabResults.isSupersededByRelease(blankCoded, analytes)).isFalse();
    }

    @Test
    @DisplayName("an untyped released row supersedes nothing either")
    void anUntypedReleasedRowContributesNoAnalyte() {
        LabOrder order = order();
        LabResult pending = row(order, "HGB", false);
        LabResult releasedUntyped = row(order, null, true);

        Set<SupersededLabResults.AnalyteKey> analytes =
            SupersededLabResults.releasedAnalytes(List.of(pending, releasedUntyped));

        assertThat(analytes).isEmpty();
        assertThat(SupersededLabResults.isSupersededByRelease(pending, analytes)).isFalse();
    }

    @Test
    @DisplayName("no released rows at all: nothing is superseded, and the set is not consulted per row")
    void withNothingReleasedNothingIsSuperseded() {
        LabOrder order = order();
        LabResult pending = row(order, "HGB", false);

        assertThat(SupersededLabResults.releasedAnalytes(List.of(pending))).isEmpty();
        assertThat(SupersededLabResults.isSupersededByRelease(pending, Set.of())).isFalse();
    }
}
