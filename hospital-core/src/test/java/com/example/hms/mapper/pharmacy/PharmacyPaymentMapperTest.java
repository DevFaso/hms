package com.example.hms.mapper.pharmacy;

import com.example.hms.enums.PharmacyPaymentStatus;
import com.example.hms.model.pharmacy.PharmacyPayment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D404: the patient's payment list keeps voided payments (they are history),
 * so every client that totals it (portal, Android, iOS) leaves out the ones whose
 * status is VOIDED. That only works while the response carries the status.
 */
class PharmacyPaymentMapperTest {

    private final PharmacyPaymentMapper mapper = new PharmacyPaymentMapper();

    @Test
    void voidedPaymentCarriesItsStatusSoClientsCanLeaveItOutOfThePaidTotal() {
        PharmacyPayment payment = new PharmacyPayment();
        payment.setAmount(new BigDecimal("500"));
        payment.setStatus(PharmacyPaymentStatus.VOIDED);

        assertThat(mapper.toResponseDTO(payment).getStatus()).isEqualTo("VOIDED");
    }

    @Test
    void postedPaymentCarriesItsStatus() {
        PharmacyPayment payment = new PharmacyPayment();
        payment.setAmount(new BigDecimal("500"));
        payment.setStatus(PharmacyPaymentStatus.POSTED);

        assertThat(mapper.toResponseDTO(payment).getStatus()).isEqualTo("POSTED");
    }
}
