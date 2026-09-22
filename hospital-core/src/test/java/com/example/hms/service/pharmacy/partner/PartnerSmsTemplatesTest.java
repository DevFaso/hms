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
                "HMS Rx ABC12 : Paracétamol 500mg pour JD. Répondez 1 pour accepter, 2 pour refuser.");
    }

    @Test
    void reminderContainsRef() {
        assertThat(templates.reminder("ABC12")).contains("ABC12").contains("rappel");
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
