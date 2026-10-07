package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.pharmacy.DispenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** G15 AC-13: one reminder at P3D, claimed before it is sent. */
@ExtendWith(MockitoExtension.class)
class ReadyForCollectionReminderSchedulerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @Mock private DispenseRepository dispenseRepository;
    @Mock private ReadyReminderClaimService claimService;
    @Mock private PharmacyServiceSupport support;

    private static Dispense pending() {
        Dispense d = Dispense.builder()
                .medicationName("Amoxicillin")
                .quantityRequested(BigDecimal.TEN)
                .quantityDispensed(BigDecimal.TEN)
                .status(DispenseStatus.PENDING)
                .build();
        d.setId(UUID.randomUUID());
        return d;
    }

    @Nested
    @DisplayName("the sweep")
    class Sweep {

        private ReadyForCollectionReminderScheduler scheduler;

        @BeforeEach
        void setUp() {
            scheduler = new ReadyForCollectionReminderScheduler(dispenseRepository, claimService, CLOCK);
        }

        @Test
        @DisplayName("asks for PENDING, unreminded fills older than 3 days and claims each one")
        void claimsEachDueFill() {
            Dispense first = pending();
            Dispense second = pending();
            when(dispenseRepository.findByStatusAndReadyReminderSentAtIsNullAndCreatedAtBefore(
                    DispenseStatus.PENDING, NOW.minusDays(3))).thenReturn(List.of(first, second));
            when(claimService.claimAndNotify(first.getId(), NOW)).thenReturn(true);
            when(claimService.claimAndNotify(second.getId(), NOW)).thenReturn(false);

            scheduler.sendReminders();

            verify(claimService).claimAndNotify(first.getId(), NOW);
            verify(claimService).claimAndNotify(second.getId(), NOW);
        }

        @Test
        @DisplayName("one failure does not stop the sweep")
        void oneFailureDoesNotStopTheRest() {
            Dispense first = pending();
            Dispense second = pending();
            when(dispenseRepository.findByStatusAndReadyReminderSentAtIsNullAndCreatedAtBefore(any(), any()))
                    .thenReturn(List.of(first, second));
            when(claimService.claimAndNotify(first.getId(), NOW)).thenThrow(new IllegalStateException("db"));
            when(claimService.claimAndNotify(second.getId(), NOW)).thenReturn(true);

            scheduler.sendReminders();

            verify(claimService).claimAndNotify(second.getId(), NOW);
        }

        @Test
        @DisplayName("pharmacy.ready-for-collection.reminder.enabled=false: nothing is read or sent")
        void disabled() {
            ReflectionTestUtils.setField(scheduler, "enabled", false);

            scheduler.sendReminders();

            verifyNoInteractions(dispenseRepository, claimService);
        }

        @Test
        @DisplayName("the window follows reminder-after")
        void windowFollowsTheProperty() {
            ReflectionTestUtils.setField(scheduler, "reminderAfter", java.time.Duration.ofDays(5));
            when(dispenseRepository.findByStatusAndReadyReminderSentAtIsNullAndCreatedAtBefore(
                    DispenseStatus.PENDING, NOW.minusDays(5))).thenReturn(List.of());

            scheduler.sendReminders();

            verifyNoInteractions(claimService);
        }
    }

    @Nested
    @DisplayName("the claim")
    class Claim {

        private ReadyReminderClaimService claim;

        @BeforeEach
        void setUp() {
            claim = new ReadyReminderClaimService(dispenseRepository, support);
        }

        @Test
        @DisplayName("the winner of the conditional stamp queues the reminder for the fill's patient and pharmacy")
        void winnerSends() {
            Dispense d = pending();
            Patient patient = new Patient();
            Pharmacy pharmacy = Pharmacy.builder().name("Pharmacie Centrale").build();
            d.setPatient(patient);
            d.setPharmacy(pharmacy);
            when(dispenseRepository.claimReadyReminder(d.getId(), NOW)).thenReturn(1);
            when(dispenseRepository.findById(d.getId())).thenReturn(Optional.of(d));

            assertThat(claim.claimAndNotify(d.getId(), NOW)).isTrue();
            verify(support).notifyReadyReminder(patient, pharmacy, "Amoxicillin");
        }

        @Test
        @DisplayName("a lost claim (already reminded, or no longer PENDING) sends nothing")
        void loserSendsNothing() {
            UUID id = UUID.randomUUID();
            when(dispenseRepository.claimReadyReminder(id, NOW)).thenReturn(0);

            assertThat(claim.claimAndNotify(id, NOW)).isFalse();
            verify(support, never()).notifyReadyReminder(any(), any(), any());
            verify(dispenseRepository, never()).findById(any());
        }
    }
}
