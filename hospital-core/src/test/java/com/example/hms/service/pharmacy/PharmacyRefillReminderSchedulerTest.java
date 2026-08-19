package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.repository.pharmacy.DispenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-39: tests for the refill reminder scheduler, covering duration parsing and
 * the day-by-day runout match.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PharmacyRefillReminderScheduler")
class PharmacyRefillReminderSchedulerTest {

    @Mock private DispenseRepository dispenseRepository;
    @Mock private PharmacyServiceSupport support;

    @InjectMocks private PharmacyRefillReminderScheduler scheduler;

    @BeforeEach
    void setDefaults() {
        ReflectionTestUtils.setField(scheduler, "leadDays", 3);
        ReflectionTestUtils.setField(scheduler, "lookbackDays", 60);
    }

    @Nested
    @DisplayName("duration parsing")
    class Parsing {

        @Test
        void parsesIntegerDays() {
            assertThat(PharmacyRefillReminderScheduler.parseDurationDays("7 days")).isEqualTo(7);
            assertThat(PharmacyRefillReminderScheduler.parseDurationDays("14 jours")).isEqualTo(14);
            assertThat(PharmacyRefillReminderScheduler.parseDurationDays("30")).isEqualTo(30);
        }

        @Test
        void convertsWeeksToDays() {
            assertThat(PharmacyRefillReminderScheduler.parseDurationDays("2 semaines")).isEqualTo(14);
            assertThat(PharmacyRefillReminderScheduler.parseDurationDays("1 week")).isEqualTo(7);
        }

        @Test
        void ignoresMonthsAndYearsAsTooImprecise() {
            assertThat(PharmacyRefillReminderScheduler.parseDurationDays("1 mois")).isNull();
            assertThat(PharmacyRefillReminderScheduler.parseDurationDays("1 year")).isNull();
        }

        @Test
        void returnsNullOnBlankOrUnparseable() {
            assertThat(PharmacyRefillReminderScheduler.parseDurationDays(null)).isNull();
            assertThat(PharmacyRefillReminderScheduler.parseDurationDays("")).isNull();
            assertThat(PharmacyRefillReminderScheduler.parseDurationDays("indefinite")).isNull();
        }
    }

    @Test
    @DisplayName("sends a reminder for a dispense whose runout falls exactly leadDays ahead")
    void sendsReminderWhenRunoutMatchesLeadDays() {
        Patient patient = patient();
        Prescription rx = prescriptionWithDuration("10 days");
        // Dispensed 7 days ago, 10-day supply → runout in 3 days (= leadDays)
        Dispense d = dispenseFor(rx, patient, LocalDateTime.now().minusDays(7));

        when(dispenseRepository.findByStatusAndDispensedAtBetween(
                eq(DispenseStatus.COMPLETED), any(), any()))
                .thenReturn(List.of(d));

        scheduler.sendDailyRefillReminders();

        verify(support).notifyRefillReminder(patient, d.getMedicationName(), 3);
    }

    @Test
    @DisplayName("does not send when runout is not yet at leadDays")
    void skipsWhenRunoutTooFar() {
        Patient patient = patient();
        Prescription rx = prescriptionWithDuration("30 days");
        // Dispensed today → runout in 30 days (not 3)
        Dispense d = dispenseFor(rx, patient, LocalDateTime.now());

        when(dispenseRepository.findByStatusAndDispensedAtBetween(any(), any(), any()))
                .thenReturn(List.of(d));

        scheduler.sendDailyRefillReminders();

        verify(support, never()).notifyRefillReminder(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("skips dispenses whose duration cannot be parsed")
    void skipsUnparseableDuration() {
        Patient patient = patient();
        Prescription rx = prescriptionWithDuration("indéfini");
        Dispense d = dispenseFor(rx, patient, LocalDateTime.now().minusDays(7));

        when(dispenseRepository.findByStatusAndDispensedAtBetween(any(), any(), any()))
                .thenReturn(List.of(d));

        scheduler.sendDailyRefillReminders();

        verify(support, never()).notifyRefillReminder(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("is a no-op when the repository returns no candidates")
    void noopOnEmptyWindow() {
        when(dispenseRepository.findByStatusAndDispensedAtBetween(any(), any(), any()))
                .thenReturn(List.of());

        scheduler.sendDailyRefillReminders();

        verify(support, never()).notifyRefillReminder(any(), anyString(), anyInt());
    }

    // ─── helpers ───

    private Patient patient() {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setFirstName("Awa");
        p.setPhoneNumberPrimary("+22670000000");
        return p;
    }

    private Prescription prescriptionWithDuration(String duration) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setDuration(duration);
        return rx;
    }

    private Dispense dispenseFor(Prescription rx, Patient patient, LocalDateTime dispensedAt) {
        Dispense d = new Dispense();
        d.setId(UUID.randomUUID());
        d.setPrescription(rx);
        d.setPatient(patient);
        d.setMedicationName("Amoxicilline 500mg");
        d.setDispensedAt(dispensedAt);
        d.setStatus(DispenseStatus.COMPLETED);
        return d;
    }

    @Test
    @DisplayName("skips dispense with null prescription")
    void skipsNullPrescription() {
        Dispense d = dispenseFor(null, patient(), LocalDateTime.now().minusDays(7));

        when(dispenseRepository.findByStatusAndDispensedAtBetween(any(), any(), any()))
                .thenReturn(List.of(d));

        scheduler.sendDailyRefillReminders();

        verify(support, never()).notifyRefillReminder(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("skips dispense with null dispensedAt")
    void skipsNullDispensedAt() {
        Prescription rx = prescriptionWithDuration("10 days");
        Dispense d = dispenseFor(rx, patient(), null);

        when(dispenseRepository.findByStatusAndDispensedAtBetween(any(), any(), any()))
                .thenReturn(List.of(d));

        scheduler.sendDailyRefillReminders();

        verify(support, never()).notifyRefillReminder(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("skips dispense whose duration is at or below leadDays")
    void skipsDurationBelowLeadDays() {
        Prescription rx = prescriptionWithDuration("3 days");
        Dispense d = dispenseFor(rx, patient(), LocalDateTime.now());

        when(dispenseRepository.findByStatusAndDispensedAtBetween(any(), any(), any()))
                .thenReturn(List.of(d));

        scheduler.sendDailyRefillReminders();

        verify(support, never()).notifyRefillReminder(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("continues processing when one dispense throws")
    void continuesAfterException() {
        Patient p = patient();
        Prescription good = prescriptionWithDuration("10 days");
        Dispense goodDispense = dispenseFor(good, p, LocalDateTime.now().minusDays(7));

        org.mockito.Mockito.doThrow(new RuntimeException("sms boom"))
                .when(support).notifyRefillReminder(any(), anyString(), anyInt());

        when(dispenseRepository.findByStatusAndDispensedAtBetween(any(), any(), any()))
                .thenReturn(List.of(goodDispense));

        // Must not throw
        scheduler.sendDailyRefillReminders();

        verify(support).notifyRefillReminder(any(), anyString(), anyInt());
    }
}
