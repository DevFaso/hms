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
    void reminderContainsRef() {
        assertThat(PartnerSmsTemplates.reminder("ABC12")).contains("ABC12").contains("rappel");
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
