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

        // Feed back the literal strings the pharmacy is told to send, lifted out
        // of the message itself — guillemets included. Hand-stripping them here
        // would test a reply nobody was asked to send.
        String accept = quoted(msg, 0);
        String reject = quoted(msg, 1);
        assertThat(accept).isEqualTo("« 1 ABC12 »");
        assertThat(reject).isEqualTo("« 2 ABC12 »");

        assertThat(parser.parse(accept)).hasValueSatisfying(r -> {
            assertThat(r.action()).isEqualTo(PartnerSmsReplyParser.Action.ACCEPT);
            assertThat(r.refToken()).isEqualTo("ABC12");
        });
        assertThat(parser.parse(reject)).hasValueSatisfying(r -> {
            assertThat(r.action()).isEqualTo(PartnerSmsReplyParser.Action.REJECT);
            assertThat(r.refToken()).isEqualTo("ABC12");
        });
    }

    /** The n-th « … » run of the message, as the pharmacy would copy it. */
    private static String quoted(String message, int index) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("«[^»]*»").matcher(message);
        for (int i = 0; i <= index; i++) {
            assertThat(m.find()).as("quoted reply #%d present", index).isTrue();
        }
        return m.group();
    }

    @Test
    void supersededTellsThePharmacyToStopPreparingWithoutClaimingATimeout() {
        String msg = PartnerSmsTemplates.superseded("ABC12");
        assertThat(msg).contains("ABC12").contains("autre pharmacie");
        // autoRejected's "délai dépassé" would be a lie here: nothing timed out.
        assertThat(msg).doesNotContain("délai");
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
