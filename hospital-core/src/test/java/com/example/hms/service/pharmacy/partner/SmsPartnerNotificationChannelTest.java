package com.example.hms.service.pharmacy.partner;

import com.example.hms.service.notification.PatientChannelConsent.Decision;

import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.service.SmsService;
import com.example.hms.service.i18n.PatientLocaleResolver;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private PatientLocaleResolver patientLocaleResolver;

    @Mock private com.example.hms.service.notification.PatientChannelConsent consent;
    @Mock private com.example.hms.service.notification.PatientNotificationLedger ledger;
    @Mock private com.example.hms.service.EmailService email;
    @Mock private com.example.hms.service.notification.PatientRecipientCurrentCheck currentCheck;
    private SmsPartnerNotificationChannel channel;

    private PrescriptionRoutingDecision decision;
    private Prescription prescription;
    private Pharmacy partner;
    private Patient patient;
    private UUID decisionId;

    @BeforeEach
    void setUp() {
        channel = new SmsPartnerNotificationChannel(new PartnerPharmacySmsTransport(smsServiceProvider),
                new com.example.hms.service.notification.PatientMessageGate(consent, ledger, smsService, email,
                    new com.example.hms.service.notification.PatientRecipientVerification(), currentCheck),
                new PartnerSmsTemplates(TestMessageSources.bundles()), patientLocaleResolver);
        lenient().when(smsService.deliversRealSms()).thenReturn(true);
        lenient().when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);
        lenient().when(consent.decision(any(), any(), any()))
            .thenAnswer(call -> new Decision(true, call.getArgument(0), null, null));
        lenient().when(patientLocaleResolver.resolve(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));

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
        patient.setVerifiedSmsRecipient("+22670111111");

        prescription = new Prescription();
        prescription.setId(UUID.randomUUID());
        prescription.setMedicationName("Amoxicilline 500mg");
        prescription.setPatient(patient);
        patient.setId(UUID.randomUUID());
        com.example.hms.model.Hospital hospital = new com.example.hms.model.Hospital();
        hospital.setId(UUID.randomUUID());
        prescription.setHospital(hospital);
        decision.setPrescription(prescription);
    }

    // ---------- buildRefToken ----------

    @Test
    @DisplayName("buildRefToken returns first 8 chars uppercased")
    void buildRefTokenNormal() {
        String token = channel.buildRefToken(decision);
        assertThat(token)
                .hasSize(8)
                .isEqualTo(decisionId.toString().substring(0, 8).toUpperCase());
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

    @Test
    void allPartnerNoticesRefuseALoggingOnlyGateway() {
        when(smsService.deliversRealSms()).thenReturn(false);
        channel.sendPrescriptionOffer(decision, prescription, partner);
        channel.sendReminder(decision, partner);
        channel.sendAutoRejected(decision, partner);
        channel.sendSuperseded(decision, partner);
        channel.sendWithdrawn(decision, partner);
        channel.sendHeldForReview(decision, partner);
        verify(smsService, never()).send(anyString(), anyString());
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
    @DisplayName("prescriptionOfferBody frames the caller's summary with token, initials and reply codes")
    void prescriptionOfferBodyFramesSummary() {
        String body = channel.prescriptionOfferBody(decision, prescription, "Amoxicilline 500mg PO BID x 7 j");

        assertThat(body)
                .startsWith("HMS Rx " + decisionId.toString().substring(0, 8).toUpperCase())
                .contains("Amoxicilline 500mg PO BID x 7 j")
                .contains("pour AB")
                .endsWith("« 2 " + decisionId.toString().substring(0, 8).toUpperCase() + " » pour refuser.");
        verifyNoInteractions(smsServiceProvider);
    }

    @Test
    @DisplayName("prescriptionOfferBody falls back to the generic medication word and em-dash initials")
    void prescriptionOfferBodyFallbacks() {
        String body = channel.prescriptionOfferBody(decision, null, " ");

        assertThat(body).contains("m\u00e9dicament").contains("pour \u2014");
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
    @DisplayName("an offer for the remainder of a partially filled order names the amount still owed")
    void sendPrescriptionOfferNamesTheRemainder() {
        prescription.setQuantity(new java.math.BigDecimal("10.00"));
        prescription.setQuantityUnit("comprimés");
        decision.setRemainingQuantity(new java.math.BigDecimal("6.00"));
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendPrescriptionOffer(decision, prescription, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(anyString(), msg.capture());
        assertThat(msg.getValue()).contains("reste 6 comprim");
        // The full prescribed amount must not appear in place of the remainder.
        // Checked as the quantity it would be printed as, not as a bare "10":
        // the reply instructions now quote the reference, which can itself
        // contain those digits (e.g. 6AF10D29).
        assertThat(msg.getValue()).doesNotContain("10 comprim").doesNotContain("reste 10");
    }

    @Test
    @DisplayName("an untouched prescription with a released refill is offered in full, not as a remainder")
    void releasedRefillIsNotAnnouncedAsARemainder() {
        // Lifetime entitlement is quantity × (1 + refillsUsed): comparing the
        // remainder with the per-fill quantity announced "(reste 20)" on an
        // order nothing had been dispensed against.
        prescription.setQuantity(new java.math.BigDecimal("10.00"));
        prescription.setRefillsUsed(1);
        decision.setRemainingQuantity(new java.math.BigDecimal("20.00"));
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendPrescriptionOffer(decision, prescription, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(anyString(), msg.capture());
        assertThat(msg.getValue()).doesNotContain("reste");
    }

    @Test
    @DisplayName("an offer for the full prescribed amount carries no remainder clause")
    void sendPrescriptionOfferForTheFullAmountHasNoRemainder() {
        prescription.setQuantity(new java.math.BigDecimal("10.00"));
        decision.setRemainingQuantity(new java.math.BigDecimal("10.00"));
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendPrescriptionOffer(decision, prescription, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(anyString(), msg.capture());
        assertThat(msg.getValue()).doesNotContain("reste");
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,true", "1,false", "3,true", "3,false"})
    void realPartnerReplyEndpointOnlyNotifiesTheVerifiedPatient(int action, boolean verified) throws Exception {
        var decisions = org.mockito.Mockito.mock(com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository.class);
        var prescriptions = org.mockito.Mockito.mock(com.example.hms.repository.PrescriptionRepository.class);
        var audit = org.mockito.Mockito.mock(com.example.hms.service.AuditEventLogService.class);
        var prescriber = org.mockito.Mockito.mock(com.example.hms.service.pharmacy.PrescriberPharmacyNotifier.class);
        var stay = org.mockito.Mockito.mock(com.example.hms.service.StayOrderDiscontinuation.class);
        prescription.setStatus(action == 1 ? com.example.hms.enums.PrescriptionStatus.SENT_TO_PARTNER
            : com.example.hms.enums.PrescriptionStatus.PARTNER_ACCEPTED);
        decision.setPrescription(prescription); decision.setTargetPharmacy(partner); decision.setDecidedForPatient(patient);
        decision.setRoutingType(com.example.hms.enums.RoutingType.PARTNER);
        decision.setStatus(action == 1 ? com.example.hms.enums.RoutingDecisionStatus.PENDING
            : com.example.hms.enums.RoutingDecisionStatus.ACCEPTED);
        if (!verified) patient.setPhoneNumberPrimary("+22670999999");
        String token = decisionId.toString().substring(0, 8);
        when(decisions.findOpenByIdPrefix(com.example.hms.enums.RoutingType.PARTNER,
            PartnerExchangeService.OPEN_STATUSES, token.toLowerCase(java.util.Locale.ROOT))).thenReturn(java.util.List.of(decision));
        when(decisions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var exchange = new PartnerExchangeService(decisions, prescriptions, channel, new PartnerSmsReplyParser(), audit,
            prescriber, new WithdrawnOrderPartnerHandler(decisions, channel, audit, prescriber), stay, "226");
        var controller = new com.example.hms.controller.pharmacy.PartnerSmsWebhookController(exchange);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "configuredSecret", "local-test-only");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/webhooks/partner-sms")
            .header("X-HMS-Partner-Signature", "local-test-only").contentType("application/json")
            .content("{\"from\":\"+22670000000\",\"body\":\"" + action + " " + token + "\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("applied"));
        if (verified) verify(smsService).send(eq("+22670111111"), anyString());
        else verify(smsService, never()).send(anyString(), anyString());
        verifyNoInteractions(smsServiceProvider);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void bothPatientNoticesRequireCurrentPrimaryProofAndConsent(boolean dispensed) {
        patient.setPhoneNumberPrimary("+22670999999"); // setter invalidates old proof
        if (dispensed) channel.notifyPatientDispensed(decision, patient, partner);
        else channel.notifyPatientAccepted(decision, patient, partner);
        verify(smsService, never()).send(anyString(), anyString());
        verifyNoInteractions(smsServiceProvider);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void bothPatientNoticesRespectOptOut(boolean dispensed) {
        when(consent.decision(any(), any(), any()))
            .thenAnswer(call -> new Decision(false, call.getArgument(0), null, null));
        if (dispensed) channel.notifyPatientDispensed(decision, patient, partner);
        else channel.notifyPatientAccepted(decision, patient, partner);
        verify(smsService, never()).send(anyString(), anyString());
    }

    // ---------- notifyPatientAccepted ----------

    @Test
    @DisplayName("notifyPatientAccepted sends SMS to patient primary phone")
    void notifyPatientAcceptedPrimary() {

        channel.notifyPatientAccepted(decision, patient, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq("+22670111111"), msg.capture());
        assertThat(msg.getValue()).contains("Pharmacie Centrale");
        assertThat(msg.getValue()).contains("accept\u00e9e");
    }

    @Test
    @DisplayName("notifyPatientAccepted never falls back to an unverifiable secondary phone")
    void notifyPatientAcceptedSecondaryFallback() {
        patient.setPhoneNumberPrimary("   ");
        patient.setPhoneNumberSecondary("+22671222222");

        channel.notifyPatientAccepted(decision, patient, partner);

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("notifyPatientAccepted is a no-op when both phones missing")
    void notifyPatientAcceptedNoPhone() {
        patient.setPhoneNumberPrimary(null);
        patient.setPhoneNumberSecondary("");

        channel.notifyPatientAccepted(decision, patient, partner);

        verifyNoInteractions(smsServiceProvider);
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("notifyPatientAccepted is a no-op when patient is null")
    void notifyPatientAcceptedNullPatient() {
        channel.notifyPatientAccepted(decision, null, partner);
        verifyNoInteractions(smsServiceProvider);
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("notifyPatientAccepted is a no-op when partner is null")
    void notifyPatientAcceptedNullPartner() {
        channel.notifyPatientAccepted(decision, patient, null);
        verifyNoInteractions(smsServiceProvider);
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("notifyPatientAccepted uses fallback name when partner name blank")
    void notifyPatientAcceptedBlankPartnerName() {
        partner.setName("   ");

        channel.notifyPatientAccepted(decision, patient, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(anyString(), msg.capture());
        assertThat(msg.getValue()).contains("pharmacie partenaire");
    }

    // ---------- notifyPatientDispensed ----------

    @Test
    @DisplayName("notifyPatientDispensed sends SMS containing partner name")
    void notifyPatientDispensedSuccess() {

        channel.notifyPatientDispensed(decision, patient, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq("+22670111111"), msg.capture());
        assertThat(msg.getValue()).contains("Pharmacie Centrale");
        assertThat(msg.getValue()).contains("d\u00e9livr\u00e9");
    }

    @Test
    @DisplayName("notifyPatientDispensed no-op when patient null")
    void notifyPatientDispensedNullPatient() {
        channel.notifyPatientDispensed(decision, null, partner);
        verifyNoInteractions(smsServiceProvider);
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("notifyPatientDispensed no-op when partner null")
    void notifyPatientDispensedNullPartner() {
        channel.notifyPatientDispensed(decision, patient, null);
        verifyNoInteractions(smsServiceProvider);
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("notifyPatientDispensed swallows gateway exceptions")
    void notifyPatientDispensedGatewayThrows() {
        doThrow(new RuntimeException("boom")).when(smsService).send(anyString(), anyString());

        // Must not throw
        channel.notifyPatientDispensed(decision, patient, partner);

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("sendWithdrawn tells the partner not to dispense, by reference only")
    void sendWithdrawnSuccess() {
        when(smsServiceProvider.getIfAvailable()).thenReturn(smsService);

        channel.sendWithdrawn(decision, partner);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq("+22670000000"), msg.capture());
        assertThat(msg.getValue())
                .contains(decisionId.toString().substring(0, 8).toUpperCase())
                .contains("Ne pas délivrer");
    }

    @Test
    @DisplayName("sendWithdrawn is a no-op without a partner phone")
    void sendWithdrawnBlankPhone() {
        partner.setPhoneNumber(" ");
        channel.sendWithdrawn(decision, partner);
        channel.sendWithdrawn(decision, null);
        verifyNoInteractions(smsServiceProvider, smsService);
    }
}
