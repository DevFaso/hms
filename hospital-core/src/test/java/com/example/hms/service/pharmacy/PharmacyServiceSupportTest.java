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
import com.example.hms.service.i18n.PatientLocaleResolver;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

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
    @Mock private PatientLocaleResolver patientLocaleResolver;

    private final MessageSource messageSource = TestMessageSources.bundles();

    private PharmacyServiceSupport support;

    @BeforeEach
    void setUp() {
        support = new PharmacyServiceSupport(roleValidator, userRepository, auditEventLogService,
                smsService, messageSource, patientLocaleResolver);
        // The fallback the caller passes is the product default; a patient
        // with no stated language gets exactly that.
        lenient().when(patientLocaleResolver.resolve(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    private PharmacyServiceSupport withoutSms() {
        return new PharmacyServiceSupport(roleValidator, userRepository, auditEventLogService,
                null, messageSource, patientLocaleResolver);
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
        support.notifyDispensed(patient(), pharmacy("Pharmacie Centrale"), "Amoxicilline");

        verify(smsService).send("+22670000000",
                "Bonjour Awa, votre ordonnance (Amoxicilline) a été délivrée à Pharmacie Centrale. Merci.");
    }

    @Test
    @DisplayName("G15: the receipt no longer claims the order is waiting to be collected")
    void receiptDoesNotSayReadyForPickup() {
        support.notifyDispensed(patient(), pharmacy("Pharmacie Centrale"), "Amoxicilline");

        verify(smsService, never()).send(anyString(), contains("prête"));
    }

    @Test
    @DisplayName("G14: a patient who stated English is written to in English")
    void dispensedReceiptFollowsThePatientsLanguage() {
        Patient p = patient();
        when(patientLocaleResolver.resolve(eq(p), any())).thenReturn(Locale.ENGLISH);

        support.notifyDispensed(p, pharmacy("Pharmacie Centrale"), "Amoxicilline");

        verify(smsService).send("+22670000000",
                "Hello Awa, your prescription (Amoxicilline) has been dispensed at Pharmacie Centrale. Thank you.");
    }

    @Test
    @DisplayName("no-op when patient is null")
    void noopWhenPatientNull() {
        support.notifyDispensed(null, pharmacy("X"), "Med");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("no-op when patient has no primary phone")
    void noopWhenNoPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary(null);
        support.notifyDispensed(p, pharmacy("X"), "Med");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("no-op when patient phone is blank")
    void noopWhenPhoneBlank() {
        Patient p = patient();
        p.setPhoneNumberPrimary("   ");
        support.notifyDispensed(p, pharmacy("X"), "Med");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("swallows SMS provider failure (does not throw)")
    void swallowsSmsFailure() {
        doThrow(new RuntimeException("provider down"))
                .when(smsService).send(anyString(), anyString());

        support.notifyDispensed(patient(), pharmacy("X"), "Med");

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("no-op when medication name is null or blank")
    void handlesNulls() {
        support.notifyDispensed(patient(), null, null);
        support.notifyDispensed(patient(), pharmacy("X"), "   ");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("does not send when smsService bean is absent")
    void noopWhenSmsServiceNull() {
        withoutSms().notifyDispensed(patient(), pharmacy("X"), "Med");
        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("notifyDispensed: uses empty first name when null")
    void dispensedEmptyFirstName() {
        Patient p = patient();
        p.setFirstName(null);
        support.notifyDispensed(p, pharmacy("X"), "Med");
        verify(smsService).send(anyString(), contains("(Med)"));
    }

    @Test
    @DisplayName("notifyDispensed: handles null pharmacy name")
    void dispensedNullPharmacyName() {
        Pharmacy ph = new Pharmacy();
        ph.setId(UUID.randomUUID());
        ph.setName(null);
        support.notifyDispensed(patient(), ph, "Med");
        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("a locale-resolver failure cannot roll back the dispense that has already happened")
    void localeResolverFailureIsSwallowed() {
        when(patientLocaleResolver.resolve(any(), any()))
                .thenThrow(new IllegalStateException("could not extract ResultSet"));

        Patient p = patient();
        assertThatCode(() -> support.notifyDispensed(p, pharmacy("X"), "Med")).doesNotThrowAnyException();
        assertThatCode(() -> support.notifyOutOfStock(p, "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT))
                .doesNotThrowAnyException();
        assertThatCode(() -> support.notifyRefillReminder(p, "Med", 3)).doesNotThrowAnyException();

        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("a missing bundle key cannot roll back the dispense either")
    void missingBundleKeyIsSwallowed() {
        PharmacyServiceSupport broken = new PharmacyServiceSupport(roleValidator, userRepository,
                auditEventLogService, smsService,
                new org.springframework.context.support.StaticMessageSource(), patientLocaleResolver);

        assertThatCode(() -> broken.notifyDispensed(patient(), pharmacy("X"), "Med"))
                .doesNotThrowAnyException();
        verify(smsService, never()).send(any(), any());
    }

    // ---------- notifyOutOfStock (T-40, G14) ----------

    @Test
    @DisplayName("T-40: out-of-stock SMS keeps the French wording and appends the partner sentence")
    void outOfStockSmsIncludesRoutingMessage() {
        support.notifyOutOfStock(patient(), "Paracétamol",
                PharmacyServiceSupport.OUT_OF_STOCK_PARTNER, "Pharmacie X");

        verify(smsService).send("+22670000000",
                "Bonjour Awa, le médicament (Paracétamol) n'est pas disponible à la pharmacie "
                        + "de l'hôpital. Elle a été envoyée à Pharmacie X.");
    }

    @Test
    @DisplayName("G14: every routing sentence resolves in the patient's language")
    void outOfStockRoutingSentencesAreLocalised() {
        Patient p = patient();
        when(patientLocaleResolver.resolve(eq(p), any())).thenReturn(Locale.ENGLISH);

        support.notifyOutOfStock(p, "Paracetamol", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        support.notifyOutOfStock(p, "Paracetamol", PharmacyServiceSupport.OUT_OF_STOCK_BACKORDER);
        support.notifyOutOfStock(p, "Paracetamol",
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
        support.notifyOutOfStock(p, "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("T-40: out-of-stock SMS swallows provider failure")
    void outOfStockSwallowsFailure() {
        doThrow(new RuntimeException("provider down"))
                .when(smsService).send(anyString(), anyString());

        support.notifyOutOfStock(patient(), "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("notifyOutOfStock: no-op when patient is null")
    void outOfStockNullPatient() {
        support.notifyOutOfStock(null, "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("notifyOutOfStock: no-op when smsService is null")
    void outOfStockNullSmsService() {
        withoutSms().notifyOutOfStock(patient(), "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("notifyOutOfStock: no-op when phone is blank")
    void outOfStockBlankPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary("  ");
        support.notifyOutOfStock(p, "Med", PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("notifyOutOfStock: handles null firstName, null medication, null routing key")
    void outOfStockHandlesAllNulls() {
        Patient p = patient();
        p.setFirstName(null);
        support.notifyOutOfStock(p, null, null);
        verify(smsService).send(anyString(),
                eq("Bonjour , le médicament () n'est pas disponible à la pharmacie de l'hôpital."));
    }

    // ---------- notifyRefillReminder (T-39, G14) ----------

    @Test
    @DisplayName("T-39: refill reminder SMS keeps the French wording")
    void refillReminderIncludesDetails() {
        support.notifyRefillReminder(patient(), "Amoxicilline", 3);

        verify(smsService).send("+22670000000",
                "Bonjour Awa, il vous reste environ 3 jours de traitement (Amoxicilline). "
                        + "Pensez à renouveler votre ordonnance. Merci.");
    }

    @Test
    @DisplayName("G14: the refill reminder follows the patient's language")
    void refillReminderFollowsThePatientsLanguage() {
        Patient p = patient();
        when(patientLocaleResolver.resolve(eq(p), any())).thenReturn(Locale.ENGLISH);

        support.notifyRefillReminder(p, "Amoxicillin", 3);

        verify(smsService).send(anyString(),
                eq("Hello Awa, you have about 3 days of treatment left (Amoxicillin). "
                        + "Remember to renew your prescription. Thank you."));
    }

    @Test
    @DisplayName("T-39: refill reminder is no-op when patient has no phone")
    void refillReminderNoopWhenNoPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary(null);
        support.notifyRefillReminder(p, "Med", 3);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("T-39: refill reminder swallows SMS failure")
    void refillReminderSwallowsFailure() {
        doThrow(new RuntimeException("provider down"))
                .when(smsService).send(anyString(), anyString());

        support.notifyRefillReminder(patient(), "Med", 3);

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("notifyRefillReminder: no-op when patient is null")
    void refillReminderNullPatient() {
        support.notifyRefillReminder(null, "Med", 3);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("notifyRefillReminder: no-op when smsService is null")
    void refillReminderNullSmsService() {
        withoutSms().notifyRefillReminder(patient(), "Med", 3);
        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("notifyRefillReminder: no-op when phone is blank")
    void refillReminderBlankPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary("  ");
        support.notifyRefillReminder(p, "Med", 3);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("notifyRefillReminder: handles null firstName and null medication")
    void refillReminderHandlesNulls() {
        Patient p = patient();
        p.setFirstName(null);
        support.notifyRefillReminder(p, null, 7);
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
}
