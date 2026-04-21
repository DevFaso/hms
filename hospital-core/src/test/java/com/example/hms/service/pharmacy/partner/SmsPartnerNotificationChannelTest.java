package com.example.hms.service.pharmacy.partner;

import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.service.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers {@link SmsPartnerNotificationChannel} — the partner SMS channel that
 * wraps the optional {@link SmsService} bean. All branches are exercised:
 * missing bean, null/blank phones, null/blank patient fields, gateway failures,
 * and template content.
 */
@ExtendWith(MockitoExtension.class)
class SmsPartnerNotificationChannelTest {

    @Mock
    private ObjectProvider<SmsService> smsServiceProvider;

    @Mock
    private SmsService smsService;

    private SmsPartnerNotificationChannel channel;

    private PrescriptionRoutingDecision decision;
    private Prescription prescription;
    private Pharmacy partner;
    private Patient patient;
    private UUID decisionId;

    @BeforeEach
    void setUp() {
        channel = new SmsPartnerNotificationChannel(smsServiceProvider);

        decisionId = UUID.randomUUID();
        decision = PrescriptionRoutingDecision.builder().build();
        decision.setId(decisionId);

        partner = Pharmacy.builder()
                .name("Pharmacie Centrale")
                .phoneNumber("+22670000000")
                .build();
        partner.setId(UUID.randomUUID());

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Alice");
        patient.setLastName("Barro");
        patient.setPhoneNumberPrimary("+22670111111");

        prescription = new Prescription();
        prescription.setId(UUID.randomUUID());
        prescription.setMedicationName("Amoxicilline 500mg");
        prescription.setPatient(patient);
    }

    // ---------- buildRefToken ----------

    @Test
    @DisplayName("buildRefToken returns first 8 chars uppercased")
    void buildRefTokenNormal() {
        String token = channel.buildRefToken(decision);
        assertThat(token).hasSize(8);
        assertThat(token).isEqualTo(decisionId.toString().substring(0, 8).toUpperCase());
    }

    @Test
    @DisplayName("buildRefToken returns empty when decision is null")
    void buildRefTokenNullDecision() {
        assertThat(channel.buildRefToken(null)).isEmpty();
    }

    @Test
    @DisplayName("buildRefToken returns empty when decision id is null")
    void buildRefTokenNullId() {
        PrescriptionRoutingDecision d = PrescriptionRoutingDecision.builder().build();
        assertThat(channel.buildRefToken(d)).isEmpty();
    }

    // ---------- sendPrescriptionOffer ----------

    @Test
    @DisplayName("sendPrescriptionOffer delivers SMS with ref token, medication and initials")
    void sendPrescriptionOfferSuccess() {
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendPrescriptionOffer(decision, prescription, partner);

        ArgumentCaptor<String> phone = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(phone.capture(), msg.capture());
        assertThat(phone.getValue()).isEqualTo("+22670000000");
        assertThat(msg.getValue()).contains("Amoxicilline 500mg");
        assertThat(msg.getValue()).contains("AB"); // initials
        assertThat(msg.getValue()).contains(
                decisionId.toString().substring(0, 8).toUpperCase());
    }

    @Test
    @DisplayName("sendPrescriptionOffer is a no-op when partner is null")
    void sendPrescriptionOfferNullPartner() {
        channel.sendPrescriptionOffer(decision, prescription, null);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("sendPrescriptionOffer is a no-op when partner phone is null")
    void sendPrescriptionOfferNullPhone() {
        partner.setPhoneNumber(null);
        channel.sendPrescriptionOffer(decision, prescription, partner);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("sendPrescriptionOffer is a no-op when partner phone is blank")
    void sendPrescriptionOfferBlankPhone() {
        partner.setPhoneNumber("   ");
        channel.sendPrescriptionOffer(decision, prescription, partner);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("sendPrescriptionOffer is a no-op when prescription is null")
    void sendPrescriptionOfferNullPrescription() {
        channel.sendPrescriptionOffer(decision, null, partner);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("sendPrescriptionOffer uses fallback medication text when name blank")
    void sendPrescriptionOfferBlankMedication() {
        prescription.setMedicationName("  ");
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendPrescriptionOffer(decision, prescription, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(anyString(), msg.capture());
        assertThat(msg.getValue()).contains("m\u00e9dicament");
    }

    @Test
    @DisplayName("sendPrescriptionOffer uses em-dash for patient with no name")
    void sendPrescriptionOfferNullPatient() {
        prescription.setPatient(null);
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendPrescriptionOffer(decision, prescription, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(anyString(), msg.capture());
        assertThat(msg.getValue()).contains("\u2014");
    }

    @Test
    @DisplayName("sendPrescriptionOffer uses em-dash when names are blank")
    void sendPrescriptionOfferBlankNames() {
        patient.setFirstName("   ");
        patient.setLastName(null);
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendPrescriptionOffer(decision, prescription, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(anyString(), msg.capture());
        assertThat(msg.getValue()).contains("\u2014");
    }

    @Test
    @DisplayName("sendPrescriptionOffer uses only first initial when last name missing")
    void sendPrescriptionOfferOnlyFirstInitial() {
        patient.setLastName(null);
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendPrescriptionOffer(decision, prescription, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(anyString(), msg.capture());
        assertThat(msg.getValue()).contains(" pour A.");
        // "pour A" should appear — build uses "pour " + initials + "."
        assertThat(msg.getValue()).doesNotContain(" pour AB.");
    }

    @Test
    @DisplayName("sendPrescriptionOffer silently swallows SMS gateway exceptions")
    void sendPrescriptionOfferGatewayThrows() {
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);
        doThrow(new RuntimeException("gateway down")).when(smsService).send(anyString(), anyString());

        // Must not throw
        channel.sendPrescriptionOffer(decision, prescription, partner);

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("sendPrescriptionOffer skips silently when SmsService bean absent")
    void sendPrescriptionOfferNoSmsService() {
        when(smsServiceProvider.getIfAvailable()).thenReturn(null);

        channel.sendPrescriptionOffer(decision, prescription, partner);

        verify(smsService, never()).send(anyString(), anyString());
    }

    // ---------- sendReminder ----------

    @Test
    @DisplayName("sendReminder delivers SMS to partner phone")
    void sendReminderSuccess() {
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendReminder(decision, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq("+22670000000"), msg.capture());
        assertThat(msg.getValue()).contains("rappel");
        assertThat(msg.getValue()).contains(
                decisionId.toString().substring(0, 8).toUpperCase());
    }

    @Test
    @DisplayName("sendReminder is a no-op when partner is null")
    void sendReminderNullPartner() {
        channel.sendReminder(decision, null);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("sendReminder is a no-op when partner phone blank")
    void sendReminderBlankPhone() {
        partner.setPhoneNumber("");
        channel.sendReminder(decision, partner);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    // ---------- sendAutoRejected ----------

    @Test
    @DisplayName("sendAutoRejected delivers SMS to partner")
    void sendAutoRejectedSuccess() {
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendAutoRejected(decision, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq("+22670000000"), msg.capture());
        assertThat(msg.getValue()).contains("d\u00e9lai");
    }

    @Test
    @DisplayName("sendAutoRejected no-op when partner null")
    void sendAutoRejectedNullPartner() {
        channel.sendAutoRejected(decision, null);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("sendAutoRejected no-op when partner phone null")
    void sendAutoRejectedNullPhone() {
        partner.setPhoneNumber(null);
        channel.sendAutoRejected(decision, partner);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    // ---------- notifyPatientAccepted ----------

    @Test
    @DisplayName("notifyPatientAccepted sends SMS to patient primary phone")
    void notifyPatientAcceptedPrimary() {
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.notifyPatientAccepted(patient, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq("+22670111111"), msg.capture());
        assertThat(msg.getValue()).contains("Pharmacie Centrale");
        assertThat(msg.getValue()).contains("accept\u00e9e");
    }

    @Test
    @DisplayName("notifyPatientAccepted falls back to secondary phone when primary blank")
    void notifyPatientAcceptedSecondaryFallback() {
        patient.setPhoneNumberPrimary("   ");
        patient.setPhoneNumberSecondary("+22671222222");
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.notifyPatientAccepted(patient, partner);

        verify(smsService).send(eq("+22671222222"), anyString());
    }

    @Test
    @DisplayName("notifyPatientAccepted is a no-op when both phones missing")
    void notifyPatientAcceptedNoPhone() {
        patient.setPhoneNumberPrimary(null);
        patient.setPhoneNumberSecondary("");

        channel.notifyPatientAccepted(patient, partner);

        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("notifyPatientAccepted is a no-op when patient is null")
    void notifyPatientAcceptedNullPatient() {
        channel.notifyPatientAccepted(null, partner);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("notifyPatientAccepted is a no-op when partner is null")
    void notifyPatientAcceptedNullPartner() {
        channel.notifyPatientAccepted(patient, null);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("notifyPatientAccepted uses fallback name when partner name blank")
    void notifyPatientAcceptedBlankPartnerName() {
        partner.setName("   ");
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.notifyPatientAccepted(patient, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(anyString(), msg.capture());
        assertThat(msg.getValue()).contains("pharmacie partenaire");
    }

    // ---------- notifyPatientDispensed ----------

    @Test
    @DisplayName("notifyPatientDispensed sends SMS containing partner name")
    void notifyPatientDispensedSuccess() {
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.notifyPatientDispensed(patient, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq("+22670111111"), msg.capture());
        assertThat(msg.getValue()).contains("Pharmacie Centrale");
        assertThat(msg.getValue()).contains("d\u00e9livr\u00e9");
    }

    @Test
    @DisplayName("notifyPatientDispensed no-op when patient null")
    void notifyPatientDispensedNullPatient() {
        channel.notifyPatientDispensed(null, partner);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("notifyPatientDispensed no-op when partner null")
    void notifyPatientDispensedNullPartner() {
        channel.notifyPatientDispensed(patient, null);
        verifyNoInteractions(smsServiceProvider, smsService);
    }

    @Test
    @DisplayName("notifyPatientDispensed swallows gateway exceptions")
    void notifyPatientDispensedGatewayThrows() {
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);
        doThrow(new RuntimeException("boom")).when(smsService).send(anyString(), anyString());

        // Must not throw
        channel.notifyPatientDispensed(patient, partner);

        verify(smsService).send(anyString(), anyString());
    }
}
