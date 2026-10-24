package com.example.hms.service.lab;

import com.example.hms.enums.ActorType;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** QA re-test D740/D741: what a reader outside the laboratory sees of stored lab results. */
class ClinicalLabResultViewTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 24, 9, 0);

    private static LabOrder order() {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        return order;
    }

    private static LabResult row(LabOrder order, String value, LocalDateTime at, boolean released) {
        LabResult r = new LabResult();
        r.setId(UUID.randomUUID());
        r.setLabOrder(order);
        r.setResultValue(value);
        r.setResultDate(at);
        r.setReleased(released);
        return r;
    }

    private static List<String> values(List<LabResult> rows) {
        return rows.stream().map(LabResult::getResultValue).toList();
    }

    @Test
    void aReleasedCorrectionHidesTheResultItNames_evenWithoutTheBackLink() {
        LabOrder kal = order();
        LabResult original = row(kal, "4.1", T0, true);
        LabResult correction = row(kal, "4.0", T0.plusMinutes(10), true);
        correction.setSupersedesResultId(original.getId());

        assertThat(values(ClinicalLabResultView.current(List.of(correction, original)))).containsExactly("4.0");
    }

    @Test
    void theBackLinkAloneHidesTheSupersededRow_whenTheCorrectionIsOffThePage() {
        LabResult original = row(order(), "4.1", T0, true);
        original.setSupersededByResultId(UUID.randomUUID());

        assertThat(ClinicalLabResultView.current(List.of(original))).isEmpty();
        assertThat(ClinicalLabResultView.isReleasedAndCurrent(original)).isFalse();
    }

    @Test
    void anUnreleasedCorrectionLeavesTheReleasedValueStanding() {
        LabOrder kal = order();
        LabResult original = row(kal, "4.1", T0, true);
        LabResult pending = row(kal, "4.0", T0.plusMinutes(10), false);
        pending.setSupersedesResultId(original.getId());

        assertThat(values(ClinicalLabResultView.current(List.of(pending, original)))).containsExactly("4.1");
        assertThat(values(ClinicalLabResultView.releasedAndCurrent(List.of(pending, original)))).containsExactly("4.1");
    }

    @Test
    void legacyDuplicates_aPendingRowBesideAReleasedOneOnTheSameOrderIsHidden() {
        // The re-test's TDR: "en attente" next to "Positif" on one order.
        LabOrder tdr = order();
        LabResult pending = row(tdr, "en attente", T0.plusMinutes(30), false);
        LabResult positive = row(tdr, "Positif", T0, true);

        assertThat(values(ClinicalLabResultView.current(List.of(pending, positive)))).containsExactly("Positif");
    }

    @Test
    void legacyDuplicates_twoReleasedRowsOnOneOrderShowOnlyTheNewest() {
        LabOrder tdr = order();
        LabResult first = row(tdr, "Positif", T0, true);
        LabResult second = row(tdr, "Positif", T0.plusHours(1), true);

        assertThat(ClinicalLabResultView.current(List.of(first, second))).containsExactly(second);
    }

    @Test
    void anAnalyzerTimedSeriesOnOneOrderIsNotCollapsed() {
        LabOrder glu = order();
        LabResult draw1 = row(glu, "5.1", T0, true);
        LabResult draw2 = row(glu, "7.9", T0.plusHours(2), true);
        for (LabResult r : List.of(draw1, draw2)) {
            r.setActorType(ActorType.SYSTEM);
            r.setTestCode("GLU");
            r.setSourceSendingApplication("ANALYZER");
            r.setObservationResultStatus("F");
        }

        assertThat(ClinicalLabResultView.current(List.of(draw1, draw2))).hasSize(2);
    }

    @Test
    void unreleasedRowsShowNoValueAndExportsDropThem() {
        LabResult critical = row(order(), "1.8", T0, false);

        assertThat(ClinicalLabResultView.showsValue(critical)).isFalse();
        assertThat(ClinicalLabResultView.current(List.of(critical))).containsExactly(critical);
        assertThat(ClinicalLabResultView.releasedAndCurrent(List.of(critical))).isEmpty();
        assertThat(ClinicalLabResultView.isReleasedAndCurrent(critical)).isFalse();
    }
}
