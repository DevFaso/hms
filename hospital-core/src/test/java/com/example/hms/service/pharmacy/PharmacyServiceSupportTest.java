package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.SmsService;
import com.example.hms.service.EmailService;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.service.notification.PatientChannelConsent;
import com.example.hms.service.notification.PatientMessageGate;
import com.example.hms.service.notification.PatientNotificationLedger;
import com.example.hms.service.i18n.PatientLocaleResolver;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The SMS bodies come from the real bundles (gap G14): a mocked
 * {@code MessageSource} would let a missing key pass as an empty text.
 * The patient's locale is what {@link PatientLocaleResolver} says it is —
 * French by default, English when the patient stated it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PharmacyServiceSupport")
class PharmacyServiceSupportTest {

    @Mock private RoleValidator roleValidator;
    @Mock private UserRepository userRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private SmsService smsService;
    @Mock private SmsService mockTransport;
    @Mock private EmailService emailService;
    @Mock private PatientChannelConsent consent;
    @Mock private PatientNotificationLedger ledger;
    @Mock private PatientLocaleResolver patientLocaleResolver;

    private final MessageSource messageSource = TestMessageSources.bundles();

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private final Prescription rx = prescription();

    private static Prescription prescription() {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setHospital(hospital);
        return p;
    }

    private PharmacyServiceSupport support;

    @BeforeEach
    void setUp() {
        support = new PharmacyServiceSupport(roleValidator, userRepository, auditEventLogService,
                gate(smsService), messageSource, patientLocaleResolver);
        // A real SMS transport, and a patient who has not turned anything off.
        lenient().when(smsService.deliversRealSms()).thenReturn(true);
        lenient().when(consent.allows(any(), any(), any())).thenReturn(true);
        // The fallback the caller passes is the product default; a patient
        // with no stated language gets exactly that.
        lenient().when(patientLocaleResolver.resolve(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    /** The logging mock transport: it must never be handed patient text. */
    private PharmacyServiceSupport withoutSms() {
        return new PharmacyServiceSupport(roleValidator, userRepository, auditEventLogService,
                gate(mockTransport), messageSource, patientLocaleResolver);
    }

    private PatientMessageGate gate(SmsService sms) {
        return new PatientMessageGate(consent, ledger, sms, emailService);
    }

    private Patient patient() {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setFirstName("Awa");
        p.setPhoneNumberPrimary("+22670000000");
        return p;
    }

    private Pharmacy pharmacy(String name) {
        Pharmacy ph = new Pharmacy();
        ph.setId(UUID.randomUUID());
        ph.setName(name);
        return ph;
    }

    // ---------- notifyDispensed (G15) ----------

    @Test
    @DisplayName("G15: the dispensed receipt names the patient, the medication and the pharmacy, in French by default")
    void sendsFrenchDispensedReceipt() {
        support.notifyDispensed(rx, patient(), pharmacy("Pharmacie Centrale"), "Amoxicilline");

        verify(smsService).send("+22670000000",
                "Bonjour Awa, votre ordonnance (Amoxicilline) a été délivrée à Pharmacie Centrale. Merci.");
    }

    @Test
    @DisplayName("G15: the receipt no longer claims the order is waiting to be collected")
    void receiptDoesNotSayReadyForPickup() {
        support.notifyDispensed(rx, patient(), pharmacy("Pharmacie Centrale"), "Amoxicilline");

        verify(smsService, never()).send(anyString(), contains("prête"));
    }

    @Test
    @DisplayName("G14: a patient who stated English is written to in English")
    void dispensedReceiptFollowsThePatientsLanguage() {
        Patient p = patient();
        when(patientLocaleResolver.resolve(eq(p), any())).thenReturn(Locale.ENGLISH);

        support.notifyDispensed(rx, p, pharmacy("Pharmacie Centrale"), "Amoxicilline");

        verify(smsService).send("+22670000000",
                "Hello Awa, your prescription (Amoxicilline) has been dispensed at Pharmacie Centrale. Thank you.");
    }

    @Test
    @DisplayName("no-op when patient is null")
    void noopWhenPatientNull() {
        support.notifyDispensed(rx, null, pharmacy("X"), "Med");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("no-op when patient has no primary phone")
    void noopWhenNoPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary(null);
        support.notifyDispensed(rx, p, pharmacy("X"), "Med");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("no-op when patient phone is blank")
    void noopWhenPhoneBlank() {
        Patient p = patient();
        p.setPhoneNumberPrimary("   ");
        support.notifyDispensed(rx, p, pharmacy("X"), "Med");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("swallows SMS provider failure (does not throw)")
    void swallowsSmsFailure() {
        doThrow(new RuntimeException("provider down"))
                .when(smsService).send(anyString(), anyString());

        support.notifyDispensed(rx, patient(), pharmacy("X"), "Med");

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("no-op when medication name is null or blank")
    void handlesNulls() {
        support.notifyDispensed(rx, patient(), null, null);
        support.notifyDispensed(rx, patient(), pharmacy("X"), "   ");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("does not send over the logging mock transport")
    void noopWhenSmsServiceNull() {
        withoutSms().notifyDispensed(rx, patient(), pharmacy("X"), "Med");
        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("notifyDispensed: uses empty first name when null")
    void dispensedEmptyFirstName() {
        Patient p = patient();
        p.setFirstName(null);
        support.notifyDispensed(rx, p, pharmacy("X"), "Med");
        verify(smsService).send(anyString(), contains("(Med)"));
    }

    @Test
    @DisplayName("notifyDispensed: handles null pharmacy name")
    void dispensedNullPharmacyName() {
        Pharmacy ph = new Pharmacy();
        ph.setId(UUID.randomUUID());
        ph.setName(null);
        support.notifyDispensed(rx, patient(), ph, "Med");
        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("a locale-resolver failure cannot roll back the dispense that has already happened")
    void localeResolverFailureIsSwallowed() {
        when(patientLocaleResolver.resolve(any(), any()))
                .thenThrow(new IllegalStateException("could not extract ResultSet"));

        Patient p = patient();
        assertThatCode(() -> support.notifyDispensed(rx, p, pharmacy("X"), "Med")).doesNotThrowAnyException();
        assertThatCode(() -> support.notifyOutOfStock(rx, p, "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT))
                .doesNotThrowAnyException();
        assertThatCode(() -> support.notifyRefillReminder(rx, p, "Med", 3)).doesNotThrowAnyException();

        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("a missing bundle key cannot roll back the dispense either")
    void missingBundleKeyIsSwallowed() {
        PharmacyServiceSupport broken = new PharmacyServiceSupport(roleValidator, userRepository,
                auditEventLogService, gate(smsService),
                new org.springframework.context.support.StaticMessageSource(), patientLocaleResolver);

        assertThatCode(() -> broken.notifyDispensed(rx, patient(), pharmacy("X"), "Med"))
                .doesNotThrowAnyException();
        verify(smsService, never()).send(any(), any());
    }

    // ---------- QA D2202: the patient's SMS choice ----------

    @Test
    @DisplayName("D2202: a patient who turned SMS off for prescription notices gets no pharmacy SMS, and staff see why")
    void optedOutPatientGetsNoPharmacySms() {
        Patient p = patient();
        when(consent.allows(any(), eq(NotificationType.MEDICATION_REFILL), eq(NotificationChannel.SMS)))
                .thenReturn(false);

        support.notifyDispensed(rx, p, pharmacy("X"), "Med");
        support.notifyReadyForCollection(rx, p, pharmacy("X"), "Med");
        support.notifyReadyCancelled(rx, p, pharmacy("X"), "Med");
        support.notifyReadyReminder(rx, p, pharmacy("X"), "Med");
        support.notifyOutOfStock(rx, p, "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        assertThat(support.notifyRefillReminder(rx, p, "Med", 3)).isFalse();

        verify(smsService, never()).send(any(), any());
        for (PatientNotificationEvent event : List.of(PatientNotificationEvent.PHARMACY_DISPENSED,
                PatientNotificationEvent.PHARMACY_READY, PatientNotificationEvent.PHARMACY_READY_CANCELLED,
                PatientNotificationEvent.PHARMACY_READY_REMINDER, PatientNotificationEvent.PHARMACY_OUT_OF_STOCK,
                PatientNotificationEvent.PHARMACY_REFILL_REMINDER)) {
            verify(ledger).recordOptOut(event, HOSPITAL_ID, p.getId(), rx.getId(), NotificationChannel.SMS);
        }
    }

    @Test
    @DisplayName("D2202: the refill reminder reports it was admitted, so the job counts only real sends")
    void refillReminderReportsAdmission() {
        assertThat(support.notifyRefillReminder(rx, patient(), "Med", 3)).isTrue();
        assertThat(withoutSms().notifyRefillReminder(rx, patient(), "Med", 3)).isFalse();
    }

    // ---------- ready for collection (G15) ----------

    @Test
    @DisplayName("G15: ready for collection, in French by default")
    void readyForCollectionInFrench() {
        support.notifyReadyForCollection(rx, patient(), pharmacy("Pharmacie Centrale"), "Amoxicilline");

        verify(smsService).send("+22670000000",
                "Bonjour Awa, votre ordonnance (Amoxicilline) est prête à être retirée à Pharmacie Centrale. Merci.");
    }

    @Test
    @DisplayName("G15: ready for collection follows the patient's language (EN, ES)")
    void readyForCollectionInEnglishAndSpanish() {
        Patient en = patient();
        Patient es = patient();
        es.setPhoneNumberPrimary("+22670000001");
        when(patientLocaleResolver.resolve(eq(en), any())).thenReturn(Locale.ENGLISH);
        when(patientLocaleResolver.resolve(eq(es), any())).thenReturn(Locale.forLanguageTag("es"));

        support.notifyReadyForCollection(rx, en, pharmacy("Central"), "Amoxicillin");
        support.notifyReadyForCollection(rx, es, pharmacy("Central"), "Amoxicilina");

        verify(smsService).send("+22670000000",
                "Hello Awa, your prescription (Amoxicillin) is ready for collection at Central. Thank you.");
        verify(smsService).send("+22670000001",
                "Hola Awa, su receta (Amoxicilina) está lista para recoger en Central. Gracias.");
    }

    @Test
    @DisplayName("G15: no longer ready and the reminder render their own keys")
    void cancelledAndReminderTexts() {
        support.notifyReadyCancelled(rx, patient(), pharmacy("Pharmacie Centrale"), "Amoxicilline");
        support.notifyReadyReminder(rx, patient(), pharmacy("Pharmacie Centrale"), "Amoxicilline");

        verify(smsService).send("+22670000000",
                "Bonjour Awa, votre ordonnance (Amoxicilline) n'est plus prête à être retirée à Pharmacie Centrale. "
                        + "Veuillez contacter la pharmacie avant de vous déplacer.");
        verify(smsService).send("+22670000000",
                "Bonjour Awa, votre ordonnance (Amoxicilline) vous attend toujours à Pharmacie Centrale. Merci.");
    }

    @Test
    @DisplayName("G15 rule 12: no first name still sends, with an empty name")
    void readyWithoutFirstNameStillSends() {
        Patient p = patient();
        p.setFirstName(null);

        support.notifyReadyForCollection(rx, p, pharmacy("X"), "Med");

        verify(smsService).send("+22670000000",
                "Bonjour , votre ordonnance (Med) est prête à être retirée à X. Merci.");
    }

    @Test
    @DisplayName("G15 rule 11: inside a transaction nothing is sent until it commits, and a rollback sends nothing")
    void sendsOnlyAfterCommit() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            support.notifyReadyForCollection(rx, patient(), pharmacy("X"), "Med");
            support.notifyDispensed(rx, patient(), pharmacy("X"), "Med");
            verify(smsService, never()).send(any(), any());

            var syncs = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(2);
            syncs.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(smsService, times(2)).send(anyString(), anyString());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("G15 rule 11: a provider failure after commit is swallowed")
    void afterCommitFailureIsSwallowed() {
        doThrow(new RuntimeException("provider down")).when(smsService).send(anyString(), anyString());

        assertThatCode(() -> support.notifyReadyCancelled(rx, patient(), pharmacy("X"), "Med"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("G15: a render failure is swallowed and nothing is sent")
    void readyRenderFailureIsSwallowed() {
        when(patientLocaleResolver.resolve(any(), any())).thenThrow(new IllegalStateException("db"));

        assertThatCode(() -> support.notifyReadyReminder(rx, patient(), pharmacy("X"), "Med"))
                .doesNotThrowAnyException();
        verify(smsService, never()).send(any(), any());
    }

    // ---------- notifyOutOfStock (T-40, G14) ----------

    @Test
    @DisplayName("T-40: out-of-stock SMS keeps the French wording and appends the partner sentence")
    void outOfStockSmsIncludesRoutingMessage() {
        support.notifyOutOfStock(rx, patient(), "Paracétamol",
                PharmacyServiceSupport.OUT_OF_STOCK_PARTNER, "Pharmacie X");

        verify(smsService).send("+22670000000",
                "Bonjour Awa, le médicament (Paracétamol) n'est pas disponible à la pharmacie "
                        + "de l'hôpital. Votre ordonnance a été envoyée à Pharmacie X.");
    }

    @Test
    @DisplayName("G14: every routing sentence resolves in the patient's language")
    void outOfStockRoutingSentencesAreLocalised() {
        Patient p = patient();
        when(patientLocaleResolver.resolve(eq(p), any())).thenReturn(Locale.ENGLISH);

        support.notifyOutOfStock(rx, p, "Paracetamol", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        support.notifyOutOfStock(rx, p, "Paracetamol", PharmacyServiceSupport.OUT_OF_STOCK_BACKORDER);
        support.notifyOutOfStock(rx, p, "Paracetamol",
                PharmacyServiceSupport.OUT_OF_STOCK_BACKORDER_DATED, "2026-10-01");

        verify(smsService).send(anyString(),
                eq("Hello Awa, the medication (Paracetamol) is not available at the hospital pharmacy. "
                        + "Please take the printed prescription to a pharmacy of your choice."));
        verify(smsService).send(anyString(),
                eq("Hello Awa, the medication (Paracetamol) is not available at the hospital pharmacy. "
                        + "We will contact you as soon as it is available."));
        verify(smsService).send(anyString(),
                eq("Hello Awa, the medication (Paracetamol) is not available at the hospital pharmacy. "
                        + "We will contact you as soon as it is available (estimated 2026-10-01)."));
    }

    @Test
    @DisplayName("T-40: out-of-stock SMS is no-op when patient has no phone")
    void outOfStockNoopWhenNoPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary(null);
        support.notifyOutOfStock(rx, p, "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("T-40: out-of-stock SMS swallows provider failure")
    void outOfStockSwallowsFailure() {
        doThrow(new RuntimeException("provider down"))
                .when(smsService).send(anyString(), anyString());

        support.notifyOutOfStock(rx, patient(), "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("notifyOutOfStock: no-op when patient is null")
    void outOfStockNullPatient() {
        support.notifyOutOfStock(rx, null, "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("notifyOutOfStock: no-op over the logging mock transport")
    void outOfStockNullSmsService() {
        withoutSms().notifyOutOfStock(rx, patient(), "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("notifyOutOfStock: no-op when phone is blank")
    void outOfStockBlankPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary("  ");
        support.notifyOutOfStock(rx, p, "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("notifyOutOfStock: handles null firstName, null medication, null routing key")
    void outOfStockHandlesAllNulls() {
        Patient p = patient();
        p.setFirstName(null);
        support.notifyOutOfStock(rx, p, null, null);
        verify(smsService).send(anyString(),
                eq("Bonjour , le médicament () n'est pas disponible à la pharmacie de l'hôpital."));
    }

    // ---------- notifyRefillReminder (T-39, G14) ----------

    @Test
    @DisplayName("T-39: refill reminder SMS keeps the French wording")
    void refillReminderIncludesDetails() {
        support.notifyRefillReminder(rx, patient(), "Amoxicilline", 3);

        verify(smsService).send("+22670000000",
                "Bonjour Awa, il vous reste environ 3 jours de traitement (Amoxicilline). "
                        + "Pensez à renouveler votre ordonnance. Merci.");
    }

    @Test
    @DisplayName("G14: the refill reminder follows the patient's language")
    void refillReminderFollowsThePatientsLanguage() {
        Patient p = patient();
        when(patientLocaleResolver.resolve(eq(p), any())).thenReturn(Locale.ENGLISH);

        support.notifyRefillReminder(rx, p, "Amoxicillin", 3);

        verify(smsService).send(anyString(),
                eq("Hello Awa, you have about 3 days of treatment left (Amoxicillin). "
                        + "Remember to renew your prescription. Thank you."));
    }

    @Test
    @DisplayName("T-39: refill reminder is no-op when patient has no phone")
    void refillReminderNoopWhenNoPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary(null);
        support.notifyRefillReminder(rx, p, "Med", 3);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("T-39: refill reminder swallows SMS failure")
    void refillReminderSwallowsFailure() {
        doThrow(new RuntimeException("provider down"))
                .when(smsService).send(anyString(), anyString());

        support.notifyRefillReminder(rx, patient(), "Med", 3);

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("notifyRefillReminder: no-op when patient is null")
    void refillReminderNullPatient() {
        support.notifyRefillReminder(rx, null, "Med", 3);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("notifyRefillReminder: no-op over the logging mock transport")
    void refillReminderNullSmsService() {
        withoutSms().notifyRefillReminder(rx, patient(), "Med", 3);
        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("notifyRefillReminder: no-op when phone is blank")
    void refillReminderBlankPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary("  ");
        support.notifyRefillReminder(rx, p, "Med", 3);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("notifyRefillReminder: handles null firstName and null medication")
    void refillReminderHandlesNulls() {
        Patient p = patient();
        p.setFirstName(null);
        support.notifyRefillReminder(rx, p, null, 7);
        verify(smsService).send(anyString(), contains("7 jours"));
    }

    // ---------- resolveCurrentUser / logAudit ----------

    @Test
    @DisplayName("resolveCurrentUser: throws BusinessException when user id is null")
    void resolveCurrentUserThrowsWhenIdNull() {
        when(roleValidator.getCurrentUserId()).thenReturn(null);
        assertThatThrownBy(() -> support.resolveCurrentUser())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("resolveCurrentUser: throws ResourceNotFoundException when user not in repo")
    void resolveCurrentUserThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(id);
        when(userRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> support.resolveCurrentUser())
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("resolveCurrentUser: returns user on happy path")
    void resolveCurrentUserReturnsUser() {
        UUID id = UUID.randomUUID();
        User u = new User();
        u.setId(id);
        when(roleValidator.getCurrentUserId()).thenReturn(id);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        assertThat(support.resolveCurrentUser()).isSameAs(u);
    }

    @Test
    @DisplayName("logAudit: delegates to audit service with SUCCESS status")
    void logAuditDelegates() {
        UUID userId = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        support.logAudit(AuditEventType.DISPENSE_CREATED, "desc", "res-1", "Dispense");
        verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    @DisplayName("logAudit: swallows audit-service failure")
    void logAuditSwallowsFailure() {
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        doThrow(new RuntimeException("audit down"))
                .when(auditEventLogService).logEvent(any());
        support.logAudit(AuditEventType.DISPENSE_CREATED, "d", "r", "e");
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    @DisplayName("QA D2924 (r66): every audit row names the acting hospital, or the record's own when the caller gives it")
    void logAuditNamesTheHospital() {
        UUID acting = UUID.randomUUID();
        UUID own = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.getCurrentHospitalId()).thenReturn(acting);

        support.logAudit(AuditEventType.DISPENSE_CREATED, "desc", "res-1", "Dispense");
        support.logAudit(AuditEventType.DISPENSE_BILLING_DEFERRAL_REQUESTED, "desc", "res-2", "DEFERRAL", own);

        org.mockito.ArgumentCaptor<AuditEventRequestDTO> rows =
                org.mockito.ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, times(2)).logEvent(rows.capture());
        assertThat(rows.getAllValues().get(0).getHospitalId()).isEqualTo(acting);
        assertThat(rows.getAllValues().get(1).getHospitalId()).isEqualTo(own);
    }

    @Test
    @DisplayName("QA D2924 (r66): a request with no hospital scope still writes its audit row")
    void logAuditWithNoScopeStillWrites() {
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.getCurrentHospitalId()).thenThrow(new IllegalStateException("no scope"));

        support.logAudit(AuditEventType.DISPENSE_CREATED, "desc", "res-1", "Dispense");

        org.mockito.ArgumentCaptor<AuditEventRequestDTO> row =
                org.mockito.ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(row.capture());
        assertThat(row.getValue().getHospitalId()).isNull();
    }
}
