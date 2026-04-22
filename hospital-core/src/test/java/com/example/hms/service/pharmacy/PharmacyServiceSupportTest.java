package com.example.hms.service.pharmacy;

import com.example.hms.model.Patient;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.SmsService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("PharmacyServiceSupport")
class PharmacyServiceSupportTest {

    @Mock private RoleValidator roleValidator;
    @Mock private UserRepository userRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private SmsService smsService;

    @InjectMocks private PharmacyServiceSupport support;

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

    @Test
    @DisplayName("sends French SMS with patient first name, medication and pharmacy name")
    void sendsFrenchSms() {
        support.notifyReadyForPickup(patient(), pharmacy("Pharmacie Centrale"), "Amoxicilline");

        verify(smsService).send(eq("+22670000000"),
                contains("Bonjour Awa"));
        verify(smsService).send(anyString(),
                contains("Amoxicilline"));
        verify(smsService).send(anyString(),
                contains("Pharmacie Centrale"));
    }

    @Test
    @DisplayName("no-op when patient is null")
    void noopWhenPatientNull() {
        support.notifyReadyForPickup(null, pharmacy("X"), "Med");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("no-op when patient has no primary phone")
    void noopWhenNoPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary(null);
        support.notifyReadyForPickup(p, pharmacy("X"), "Med");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("no-op when patient phone is blank")
    void noopWhenPhoneBlank() {
        Patient p = patient();
        p.setPhoneNumberPrimary("   ");
        support.notifyReadyForPickup(p, pharmacy("X"), "Med");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("swallows SMS provider failure (does not throw)")
    void swallowsSmsFailure() {
        doThrow(new RuntimeException("provider down"))
                .when(smsService).send(anyString(), anyString());

        // Must not throw
        support.notifyReadyForPickup(patient(), pharmacy("X"), "Med");

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("handles null pharmacy and null medication gracefully")
    void handlesNulls() {
        support.notifyReadyForPickup(patient(), null, null);
        verify(smsService).send(eq("+22670000000"), anyString());
    }

    @Test
    @DisplayName("does not send when smsService bean is absent")
    void noopWhenSmsServiceNull() {
        PharmacyServiceSupport s = new PharmacyServiceSupport(
                roleValidator, userRepository, auditEventLogService, null);
        s.notifyReadyForPickup(patient(), pharmacy("X"), "Med");
        // No exception, no interactions expected on any collaborator
        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("T-40: out-of-stock SMS includes patient, medication and routing message")
    void outOfStockSmsIncludesRoutingMessage() {
        support.notifyOutOfStock(patient(), "Paracétamol", "Elle a été envoyée à Pharmacie X.");

        verify(smsService).send(eq("+22670000000"), contains("Bonjour Awa"));
        verify(smsService).send(anyString(), contains("Paracétamol"));
        verify(smsService).send(anyString(), contains("Pharmacie X"));
        verify(smsService).send(anyString(), contains("n'est pas disponible"));
    }

    @Test
    @DisplayName("T-40: out-of-stock SMS is no-op when patient has no phone")
    void outOfStockNoopWhenNoPhone() {
        Patient p = patient();
        p.setPhoneNumberPrimary(null);
        support.notifyOutOfStock(p, "Med", "msg");
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("T-40: out-of-stock swallows SMS failure")
    void outOfStockSwallowsFailure() {
        doThrow(new RuntimeException("provider down"))
                .when(smsService).send(anyString(), anyString());

        support.notifyOutOfStock(patient(), "Med", "msg");

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    @DisplayName("T-39: refill reminder SMS includes patient first name, days left and medication")
    void refillReminderIncludesDetails() {
        support.notifyRefillReminder(patient(), "Amoxicilline", 3);

        verify(smsService).send(eq("+22670000000"), contains("Bonjour Awa"));
        verify(smsService).send(anyString(), contains("3 jours"));
        verify(smsService).send(anyString(), contains("Amoxicilline"));
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
}
