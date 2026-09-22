package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * G6 — the body a prescriber reads, rendered from the real bundle in the
 * staff locale (French), addressed to the prescriber's login.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PrescriberPharmacyNotificationWriter")
class PrescriberPharmacyNotificationWriterTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private NotificationService notificationService;

    private PrescriberPharmacyNotificationWriter writer;

    private Prescription prescription;
    private final UUID prescriptionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        writer = new PrescriberPharmacyNotificationWriter(prescriptionRepository, notificationService,
                TestMessageSources.bundles());

        User account = new User();
        account.setUsername("dr.awa");
        Staff staff = new Staff();
        staff.setUser(account);
        Patient patient = new Patient();
        patient.setFirstName("Aminata");
        patient.setLastName("Diallo");

        prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setMedicationName("Amoxicilline 500 mg");
        prescription.setStaff(staff);
        prescription.setPatient(patient);
        prescription.setPharmacyName("Pharmacie du Marché");
    }

    @Test
    @DisplayName("a full fill reads as a French sentence naming the drug and the patient")
    void dispensed() {
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

        assertThat(writer.write(prescriptionId, PrescriptionStatus.DISPENSED)).isTrue();

        verify(notificationService).createNotification(
                "Pharmacie : Amoxicilline 500 mg (Aminata Diallo) a été entièrement délivré.",
                "dr.awa", "PHARMACY_EVENT");
    }

    @Test
    @DisplayName("a partner outcome names the partner pharmacy")
    void partnerRejected() {
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

        writer.write(prescriptionId, PrescriptionStatus.PARTNER_REJECTED);

        verify(notificationService).createNotification(
                "Pharmacie : Amoxicilline 500 mg (Aminata Diallo) a été refusé par la pharmacie "
                        + "partenaire Pharmacie du Marché et doit être réorienté.",
                "dr.awa", "PHARMACY_EVENT");
    }

    @Test
    @DisplayName("a clarification request carries the pharmacist's question")
    void pendingClarification() {
        prescription.setClarificationReason("Dose au-dessus du plafond rénal");
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

        writer.write(prescriptionId, PrescriptionStatus.PENDING_CLARIFICATION);

        verify(notificationService).createNotification(
                "Pharmacie : clarification demandée pour Amoxicilline 500 mg (Aminata Diallo) "
                        + "— Dose au-dessus du plafond rénal",
                "dr.awa", "PHARMACY_EVENT");
    }

    @Test
    @DisplayName("falls back to generic words when the partner or the patient has no name")
    void fallbacks() {
        prescription.setPharmacyName(null);
        prescription.setPatient(null);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

        writer.write(prescriptionId, PrescriptionStatus.PARTNER_ACCEPTED);

        verify(notificationService).createNotification(
                "Pharmacie : Amoxicilline 500 mg (patient) a été accepté par la pharmacie partenaire "
                        + "la pharmacie partenaire.",
                "dr.awa", "PHARMACY_EVENT");
    }

    @Test
    @DisplayName("writes nothing when the prescription is gone or the prescriber has no account")
    void skipsWhenUnresolvable() {
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.empty());
        assertThat(writer.write(prescriptionId, PrescriptionStatus.DISPENSED)).isFalse();

        prescription.getStaff().setUser(null);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        assertThat(writer.write(prescriptionId, PrescriptionStatus.DISPENSED)).isFalse();

        verifyNoInteractions(notificationService);
    }
}
