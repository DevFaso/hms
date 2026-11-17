package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PharmacyPaymentStatus;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.PharmacyPayment;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D404: the payment of a cancelled dispense is voided, never left counted. */
@ExtendWith(MockitoExtension.class)
class PharmacyPaymentVoiderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC);

    @Mock private PharmacyPaymentRepository paymentRepository;
    @Mock private PharmacyServiceSupport support;
    @Mock private RoleValidator roleValidator;

    private PharmacyPaymentVoider voider;
    private Dispense dispense;

    @BeforeEach
    void setUp() {
        voider = new PharmacyPaymentVoider(paymentRepository, support, roleValidator, CLOCK);
        dispense = Dispense.builder().build();
        dispense.setId(UUID.randomUUID());
    }

    @Test
    void thePaidDispenseOfTheQaScenarioIsVoidedAndAudited() {
        // QA: 4 Paracétamol, 200 paid cash, then cancel.
        PharmacyPayment payment = PharmacyPayment.builder().amount(new BigDecimal("200")).currency("XOF").build();
        payment.setId(UUID.randomUUID());
        UUID pharmacist = UUID.randomUUID();
        when(paymentRepository.findByDispense_IdAndStatus(dispense.getId(), PharmacyPaymentStatus.POSTED))
                .thenReturn(List.of(payment));
        when(roleValidator.getCurrentUserId()).thenReturn(pharmacist);
        when(paymentRepository.voidPostedForDispense(eq(dispense.getId()), eq(pharmacist), anyString(),
                any(LocalDateTime.class))).thenReturn(1);

        int voided = voider.voidForCancelledDispense(dispense, "Dispense cancelled");

        assertThat(voided).isEqualTo(1);
        assertThat(payment.getStatus()).isEqualTo(PharmacyPaymentStatus.VOIDED);
        assertThat(payment.getVoidedBy()).isEqualTo(pharmacist);
        verify(support).logAudit(eq(AuditEventType.REFUND_ISSUED), argThat(d -> d.contains("200")),
                eq(payment.getId().toString()), eq("PHARMACY_PAYMENT"));
    }

    @Test
    void anUnpaidDispenseTouchesNothing() {
        when(paymentRepository.findByDispense_IdAndStatus(dispense.getId(), PharmacyPaymentStatus.POSTED))
                .thenReturn(List.of());

        assertThat(voider.voidForCancelledDispense(dispense, "x")).isZero();

        verify(paymentRepository, never()).voidPostedForDispense(any(), any(), any(), any());
        verify(support, never()).logAudit(any(), any(), any(), any());
    }
}
