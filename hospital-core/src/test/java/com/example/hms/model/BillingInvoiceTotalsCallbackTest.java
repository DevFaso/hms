package com.example.hms.model;

import com.example.hms.enums.InvoiceStatus;
import jakarta.persistence.PrePersist;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D72: every saved invoice had total 0.00 and a new line failed with
 * "totalPrice must not be null". The invoice's lifecycle callbacks summed the
 * in-memory line collection (empty on a create, unloaded on an update) over
 * the total, and the line computed its total on update only.
 */
class BillingInvoiceTotalsCallbackTest {

    private static BillingInvoice invoice() {
        return BillingInvoice.builder()
            .invoiceNumber("GH-2026-000001")
            .invoiceDate(LocalDate.of(2026, 10, 1))
            .dueDate(LocalDate.of(2026, 10, 31))
            .totalAmount(new BigDecimal("15000.00"))
            .amountPaid(BigDecimal.ZERO)
            .status(InvoiceStatus.SENT)
            .build();
    }

    @Test
    void persistKeepsTheTotalAndTheStatus() {
        BillingInvoice invoice = invoice();
        invoice.prePersist();
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("15000");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.SENT);
    }

    @Test
    void updateKeepsTheTotal() {
        BillingInvoice invoice = invoice();
        invoice.preUpdate();
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("15000");
    }

    @Test
    void aNewLineComputesItsTotalBeforeInsert() throws NoSuchMethodException {
        assertThat(InvoiceItem.class.getMethod("validateAndCalculate").isAnnotationPresent(PrePersist.class)).isTrue();

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        BillingInvoice invoice = invoice();
        invoice.setHospital(hospital);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setHospital(hospital);
        InvoiceItem item = InvoiceItem.builder()
            .billingInvoice(invoice).assignment(assignment)
            .itemDescription("Consultation").quantity(3).unitPrice(new BigDecimal("2500")).build();

        item.validateAndCalculate();

        assertThat(item.getTotalPrice()).isEqualByComparingTo("7500");
    }
}
