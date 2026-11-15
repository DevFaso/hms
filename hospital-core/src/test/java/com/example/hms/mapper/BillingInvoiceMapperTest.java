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

    /** QA D1901: the copay's reason in each reader's language, still naming the invoice; a typed one as typed. */
    @Test
    void theServerReasonFollowsTheReaderAndATypedOneIsShownAsTyped_D1901() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        BillingInvoiceMapper mapper = new BillingInvoiceMapper();
        com.example.hms.model.BillingInvoice copay = new com.example.hms.model.BillingInvoice();
        copay.setCancelReason("Ticket modérateur reporté sur la facture CHW-2026-000020.");
        copay.setCancelReasonCode("billing.invoice.copayCreditedReason");
        copay.setCancelReasonArgs("[{\"value\":\"CHW-2026-000020\"}]");
        com.example.hms.model.BillingInvoice typed = new com.example.hms.model.BillingInvoice();
        typed.setCancelReason("Erreur de saisie");
        typed.setNotes("Pansement simple");
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
            org.assertj.core.api.Assertions.assertThat(mapper.toBillingInvoiceResponseDTO(copay).getCancelReason())
                .isEqualTo("Copay carried onto invoice CHW-2026-000020.");
            org.assertj.core.api.Assertions.assertThat(mapper.toBillingInvoiceResponseDTO(typed).getCancelReason())
                .isEqualTo("Erreur de saisie");
            org.assertj.core.api.Assertions.assertThat(mapper.toBillingInvoiceResponseDTO(typed).getNotes())
                .isEqualTo("Pansement simple");
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
            org.assertj.core.api.Assertions.assertThat(mapper.toBillingInvoiceResponseDTO(copay).getCancelReason())
                .isEqualTo("Ticket modérateur reporté sur la facture CHW-2026-000020.");
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("es"));
            org.assertj.core.api.Assertions.assertThat(mapper.toBillingInvoiceResponseDTO(copay).getCancelReason())
                .isEqualTo("Copago trasladado a la factura CHW-2026-000020.");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }
}
