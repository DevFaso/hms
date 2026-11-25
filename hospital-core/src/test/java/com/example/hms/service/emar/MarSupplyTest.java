package com.example.hms.service.emar;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D3941: what a MAR row says the pharmacy supplied. */
@ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@DisplayName("D3941: the supply on a MAR row")
class MarSupplyTest {

    private static Prescription order(PrescriptionStatus status, String quantity) {
        Prescription rx = new Prescription();
        rx.setStatus(status);
        rx.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        return rx;
    }

    @Test
    void nothingHandedOverIsNotSupplied() {
        MarSupply.Reading r = MarSupply.of(order(PrescriptionStatus.SIGNED, "9"), null);
        assertThat(r.state()).isEqualTo(MarSupply.State.NOT_SUPPLIED);
        assertThat(r.supplied()).isEqualByComparingTo("0");
        assertThat(r.ordered()).isEqualByComparingTo("9");
        assertThat(r.label()).isEqualTo("Pharmacy: not supplied");
    }

    @Test
    void threeOfNineIsPartlySupplied() {
        MarSupply.Reading r = MarSupply.of(order(PrescriptionStatus.PARTIALLY_FILLED, "9.00"), new BigDecimal("3.00"));
        assertThat(r.state()).isEqualTo(MarSupply.State.PARTIALLY_SUPPLIED);
        assertThat(r.label()).isEqualTo("Pharmacy: 3/9 supplied");
    }

    @Test
    void theWholeQuantityIsSupplied() {
        MarSupply.Reading r = MarSupply.of(order(PrescriptionStatus.DISPENSED, "9"), new BigDecimal("9"));
        assertThat(r.state()).isEqualTo(MarSupply.State.SUPPLIED);
        assertThat(r.label()).isEqualTo("Pharmacy: 9/9 supplied");
    }

    @Test
    void anOrderWithNoQuantityIsSuppliedWhenThePharmacySaysSo() {
        MarSupply.Reading r = MarSupply.of(order(PrescriptionStatus.DISPENSED, null), new BigDecimal("2.5"));
        assertThat(r.state()).isEqualTo(MarSupply.State.SUPPLIED);
        assertThat(r.ordered()).isNull();
        assertThat(r.label()).isEqualTo("Pharmacy: 2.5 supplied");
    }

    @Test
    void withNoQuantityOnRecordThePharmacysStatusIsSaid() {
        MarSupply.Reading whole = MarSupply.of(order(PrescriptionStatus.DISPENSED, "9"), null);
        assertThat(whole.state()).isEqualTo(MarSupply.State.SUPPLIED);
        assertThat(whole.label()).isEqualTo("Pharmacy: supplied");
        MarSupply.Reading part = MarSupply.of(order(PrescriptionStatus.PARTIALLY_FILLED, "9"), BigDecimal.ZERO);
        assertThat(part.state()).isEqualTo(MarSupply.State.PARTIALLY_SUPPLIED);
        assertThat(part.label()).isEqualTo("Pharmacy: partly supplied");
    }

    @Test
    void outOfStockAQuestionAndAnExternalSupplyAreSaid() {
        assertThat(MarSupply.of(order(PrescriptionStatus.PENDING_STOCK, "9"), null).state())
            .isEqualTo(MarSupply.State.OUT_OF_STOCK);
        assertThat(MarSupply.of(order(PrescriptionStatus.PENDING_STOCK, "9"), new BigDecimal("3")).state())
            .as("a partial fill waiting for the rest").isEqualTo(MarSupply.State.PARTIALLY_SUPPLIED);
        assertThat(MarSupply.of(order(PrescriptionStatus.PENDING_CLARIFICATION, "9"), null).state())
            .isEqualTo(MarSupply.State.PHARMACY_QUERY);
        for (PrescriptionStatus external : java.util.List.of(PrescriptionStatus.SENT_TO_PARTNER,
                PrescriptionStatus.PARTNER_ACCEPTED, PrescriptionStatus.PARTNER_DISPENSED, PrescriptionStatus.PRINTED_FOR_PATIENT)) {
            assertThat(MarSupply.of(order(external, "9"), null).state()).as(external.name())
                .isEqualTo(MarSupply.State.EXTERNAL);
        }
    }
}
