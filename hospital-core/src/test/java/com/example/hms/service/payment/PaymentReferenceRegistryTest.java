package com.example.hms.service.payment;

import com.example.hms.exception.BusinessException;
import com.example.hms.repository.PaymentTransactionRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.repository.pharmacy.PharmacySaleRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** QA D1129 (round 20): one provider reference is recorded once across invoice, copay, dispense and sale payments. */
class PaymentReferenceRegistryTest {

    private final UUID hospitalId = UUID.randomUUID();
    private final PaymentTransactionRepository invoicePayments = mock(PaymentTransactionRepository.class);
    private final PharmacyPaymentRepository pharmacyPayments = mock(PharmacyPaymentRepository.class);
    private final PharmacySaleRepository pharmacySales = mock(PharmacySaleRepository.class);
    private final PaymentReferenceRegistry registry =
        new PaymentReferenceRegistry(invoicePayments, pharmacyPayments, pharmacySales);

    @Test
    void theNormalFormIgnoresCaseAndSpaces() {
        assertThat(PaymentReferenceRegistry.normalize("  OM R20 77812 ")).isEqualTo("omr2077812");
        assertThat(PaymentReferenceRegistry.normalize("   ")).isNull();
        assertThat(PaymentReferenceRegistry.normalize(null)).isNull();
    }

    @Test
    void aReferenceOnAnInvoiceOrCopayPaymentIsRefused() {
        when(invoicePayments.existsLiveReference(eq(hospitalId), eq("om-r20-77812"), any())).thenReturn(true);

        assertThatThrownBy(() -> registry.requireUnused(hospitalId, " OM-R20-77812 "))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "billing.payment.duplicateReference");
    }

    @Test
    void aReferenceOnAnotherDispensePaymentIsRefused() {
        when(pharmacyPayments.existsLiveReference(hospitalId, "om-r20-55123")).thenReturn(true);

        assertThatThrownBy(() -> registry.requireUnused(hospitalId, "OM-R20-55123"))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "billing.payment.duplicateReference");
    }

    @Test
    void aReferenceOnAWalkInSaleIsRefused() {
        when(pharmacySales.existsLiveReference(hospitalId, "mtn1")).thenReturn(true);

        assertThatThrownBy(() -> registry.requireUnused(hospitalId, "MTN 1"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void aNewReferenceAndABlankOneAreAccepted() {
        when(pharmacyPayments.existsLiveReference(any(), anyString())).thenReturn(false);
        assertThatCode(() -> registry.requireUnused(hospitalId, "OM-NEW-1")).doesNotThrowAnyException();

        PaymentReferenceRegistry quiet = new PaymentReferenceRegistry(
            mock(PaymentTransactionRepository.class), mock(PharmacyPaymentRepository.class),
            mock(PharmacySaleRepository.class));
        PharmacySaleRepository untouched = mock(PharmacySaleRepository.class);
        new PaymentReferenceRegistry(mock(PaymentTransactionRepository.class), mock(PharmacyPaymentRepository.class),
            untouched).requireUnused(hospitalId, "  ");
        verifyNoInteractions(untouched);
        assertThatCode(() -> quiet.requireUnused(null, "OM-1")).doesNotThrowAnyException();
    }
}
