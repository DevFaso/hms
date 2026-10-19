package com.example.hms.mapper;

import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BillingInvoiceMapperTest {

    private final BillingInvoiceMapper mapper = new BillingInvoiceMapper();

    /** QA D148: the edit form sends the encounter code back; without it the link was dropped on save. */
    @Test
    void responseCarriesTheEncounterCode_D148() {
        Encounter encounter = new Encounter();
        encounter.setCode("HA-ENC-00042");
        BillingInvoice invoice = new BillingInvoice();
        invoice.setEncounter(encounter);

        BillingInvoiceResponseDTO dto = mapper.toBillingInvoiceResponseDTO(invoice);

        assertThat(dto.getEncounterCode()).isEqualTo("HA-ENC-00042");
    }

    @Test
    void noEncounterNoCode_D148() {
        assertThat(mapper.toBillingInvoiceResponseDTO(new BillingInvoice()).getEncounterCode()).isNull();
    }

    /** QA D202: a cancelled invoice is owed by nobody, so every consumer (portal, proxy, staff) reads 0. */
    @Test
    void cancelledInvoiceOwesNothing_D202() {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setTotalAmount(new java.math.BigDecimal("5000"));
        invoice.setAmountPaid(java.math.BigDecimal.ZERO);
        invoice.setStatus(com.example.hms.enums.InvoiceStatus.CANCELLED);

        assertThat(mapper.toBillingInvoiceResponseDTO(invoice).getBalanceDue()).isZero();
    }

    @Test
    void openInvoiceStillOwesItsBalance_D202() {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setTotalAmount(new java.math.BigDecimal("5000"));
        invoice.setAmountPaid(new java.math.BigDecimal("1000"));
        invoice.setStatus(com.example.hms.enums.InvoiceStatus.PARTIALLY_PAID);

        assertThat(mapper.toBillingInvoiceResponseDTO(invoice).getBalanceDue()).isEqualByComparingTo("4000");
    }
}
