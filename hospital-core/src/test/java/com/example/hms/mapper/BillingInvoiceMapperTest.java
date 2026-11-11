package com.example.hms.mapper;

import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BillingInvoiceMapperTest {

    private final BillingInvoiceMapper mapper = new BillingInvoiceMapper();

    /**
     * QA D1421: an invoice names its visit by type and date. Every clinical text of the encounter
     * is planted and none may reach ANY reader's answer: the mapper is the same for all of them.
     */
    @Test
    void noClinicalTextOfTheEncounterReachesTheInvoice_D1421() throws Exception {
        String planted = "CLINICAL-SENTINEL";
        Encounter encounter = new Encounter();
        encounter.setNotes(planted);
        encounter.setChiefComplaint(planted);
        encounter.setDeskChiefComplaint(planted);
        encounter.setFollowUpInstructions(planted);
        encounter.setDischargeDiagnoses(planted);
        encounter.setExtraFields(java.util.Map.of("note", planted));
        encounter.setEncounterType(com.example.hms.enums.EncounterType.INPATIENT);
        encounter.setEncounterDate(java.time.LocalDateTime.of(2026, 11, 3, 8, 30));
        BillingInvoice invoice = new BillingInvoice();
        invoice.setEncounter(encounter);

        BillingInvoiceResponseDTO dto = mapper.toBillingInvoiceResponseDTO(invoice);

        String json = new tools.jackson.databind.json.JsonMapper().writeValueAsString(dto);
        assertThat(json).doesNotContain(planted);
        assertThat(dto.getEncounterType()).isEqualTo("INPATIENT");
        assertThat(dto.getEncounterDate()).startsWith("2026-11-03");
    }

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

    /** QA D428: a folded copay names the invoice it went to, and the creator is named. */
    @Test
    void foldedCopayNamesItsVisitInvoiceAndTheCreator_D428() {
        BillingInvoice visit = new BillingInvoice();
        visit.setInvoiceNumber("RHA-2026-000021");
        com.example.hms.model.User creator = new com.example.hms.model.User();
        creator.setFirstName("Awa");
        creator.setLastName("Traore");
        BillingInvoice copay = new BillingInvoice();
        copay.setStatus(com.example.hms.enums.InvoiceStatus.CANCELLED);
        copay.setCreditedToInvoice(visit);
        copay.setCreatedByUser(creator);

        BillingInvoiceResponseDTO dto = mapper.toBillingInvoiceResponseDTO(copay);

        assertThat(dto.getCreditedToInvoiceNumber()).isEqualTo("RHA-2026-000021");
        assertThat(dto.getCreatedByName()).isEqualTo("Awa Traore");
    }

    @Test
    void noCreatorOnFileNoName_D428() {
        assertThat(mapper.toBillingInvoiceResponseDTO(new BillingInvoice()).getCreatedByName()).isNull();
    }
}
