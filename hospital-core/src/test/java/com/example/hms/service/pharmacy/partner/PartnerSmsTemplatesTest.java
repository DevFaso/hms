package com.example.hms.service.pharmacy.partner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerSmsTemplatesTest {

    @Test
    void offerContainsRefMedicationAndInitials() {
        String msg = PartnerSmsTemplates.prescriptionOffer("ABC12", "Paracétamol 500mg", "JD");
        assertThat(msg).contains("ABC12").contains("Paracétamol 500mg").contains("JD")
                .contains("1").contains("2");
    }

    @Test
    void offerTellsThePharmacyToQuoteTheReference() {
        String msg = PartnerSmsTemplates.prescriptionOffer("ABC12", "Paracétamol 500mg", "JD");
        // The parser drops a reply with no reference, so the offer must show one.
        assertThat(msg)
                .contains("« 1 ABC12 »")
                .contains("« 2 ABC12 »");
    }

    @Test
    void theInstructedReplyIsOneTheParserAccepts() {
        PartnerSmsReplyParser parser = new PartnerSmsReplyParser();
        String msg = PartnerSmsTemplates.prescriptionOffer("ABC12", "Paracétamol 500mg", "JD");

        // Lift the quoted replies straight out of the message the pharmacy reads.
        assertThat(msg).contains("« 1 ABC12 »").contains("« 2 ABC12 »");
        assertThat(parser.parse("1 ABC12")).hasValueSatisfying(r -> {
            assertThat(r.action()).isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
            assertThat(r.refToken()).isEqualTo("ABC12");
        });
        assertThat(parser.parse("2 ABC12")).hasValueSatisfying(r -> {
            assertThat(r.action()).isEqualTo(PartnerSmsReplyParser.Action.REJECT);
            assertThat(r.refToken()).isEqualTo("ABC12");
        });
    }

    @Test
    void reminderContainsRef() {
        assertThat(PartnerSmsTemplates.reminder("ABC12")).contains("ABC12").contains("rappel");
        assertThat(PartnerSmsTemplates.reminder("ABC12")).contains("« 1 ABC12 »");
    }

    @Test
    void autoRejectedMentionsTimeout() {
        assertThat(PartnerSmsTemplates.autoRejected("ABC12")).contains("ABC12").contains("délai");
    }

    @Test
    void patientAcceptedMentionsPharmacyName() {
        assertThat(PartnerSmsTemplates.patientAccepted("Pharmacie Centrale"))
                .contains("Pharmacie Centrale").contains("acceptée");
    }

    @Test
    void patientDispensedMentionsPharmacyName() {
        assertThat(PartnerSmsTemplates.patientDispensed("Pharmacie Centrale"))
                .contains("Pharmacie Centrale").contains("délivré");
    }
}
