package com.example.hms.service.pharmacy.partner;

import com.example.hms.i18n.TestMessageSources;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The partner SMS wording now lives in the bundles (gap G14). Partner-facing
 * texts render in the product's French default; patient-facing texts take
 * the caller's locale.
 */
class PartnerSmsTemplatesTest {

    private final PartnerSmsTemplates templates = new PartnerSmsTemplates(TestMessageSources.bundles());

    @Test
    void offerContainsRefMedicationAndInitials() {
        String msg = templates.prescriptionOffer("ABC12", "Paracétamol 500mg", "JD");
        assertThat(msg).isEqualTo(
                "HMS Rx ABC12 : Paracétamol 500mg pour JD. Répondez « 1 ABC12 » pour accepter, « 2 ABC12 » pour refuser.");
    }

    @Test
    void partialOfferNamesTheRemainder() {
        String msg = templates.prescriptionOfferPartial("ABC12", "Paracétamol 500mg", "6 comprimés", "JD");
        assertThat(msg).isEqualTo(
                "HMS Rx ABC12 : Paracétamol 500mg (reste 6 comprimés) pour JD. "
                        + "Répondez « 1 ABC12 » pour accepter, « 2 ABC12 » pour refuser.");
    }

    @Test
    void offerTellsThePharmacyToQuoteTheReference() {
        String msg = templates.prescriptionOffer("ABC12", "Paracétamol 500mg", "JD");
        // The parser drops a reply with no reference, so the offer must show one.
        assertThat(msg)
                .contains("« 1 ABC12 »")
                .contains("« 2 ABC12 »");
    }

    @Test
    void theInstructedReplyIsOneTheParserAccepts() {
        PartnerSmsReplyParser parser = new PartnerSmsReplyParser();
        String msg = templates.prescriptionOffer("ABC12", "Paracétamol 500mg", "JD");

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
        String msg = templates.superseded("ABC12");
        assertThat(msg).contains("ABC12").contains("autre pharmacie");
        // autoRejected's "délai dépassé" would be a lie here: nothing timed out.
        assertThat(msg).doesNotContain("délai");
    }

    @Test
    void reminderContainsRef() {
        assertThat(templates.reminder("ABC12")).contains("ABC12").contains("rappel");
        assertThat(templates.reminder("ABC12")).contains("« 1 ABC12 »");
    }

    @Test
    void autoRejectedMentionsTimeout() {
        assertThat(templates.autoRejected("ABC12")).contains("ABC12").contains("délai");
    }

    @Test
    void patientAcceptedMentionsPharmacyName() {
        assertThat(templates.patientAccepted("Pharmacie Centrale", Locale.FRENCH))
                .contains("Pharmacie Centrale").contains("acceptée");
    }

    @Test
    void patientDispensedMentionsPharmacyName() {
        assertThat(templates.patientDispensed("Pharmacie Centrale", Locale.FRENCH))
                .contains("Pharmacie Centrale").contains("délivré");
    }

    @Test
    void patientTextsFollowThePatientsLocale() {
        assertThat(templates.patientAccepted("Pharmacie Centrale", Locale.ENGLISH))
                .isEqualTo("Hello, your prescription has been accepted by Pharmacie Centrale. You may go there.");
        assertThat(templates.patientDispensed("Pharmacie Centrale", Locale.ENGLISH))
                .isEqualTo("Hello, your medication has been dispensed by Pharmacie Centrale. Take care.");
        assertThat(templates.pharmacyFallback(Locale.ENGLISH)).isEqualTo("the partner pharmacy");
    }

    @Test
    void fallbacksAreWords() {
        assertThat(templates.medicationFallback()).isEqualTo("médicament");
        assertThat(templates.pharmacyFallback(Locale.FRENCH)).isEqualTo("la pharmacie partenaire");
    }
}
