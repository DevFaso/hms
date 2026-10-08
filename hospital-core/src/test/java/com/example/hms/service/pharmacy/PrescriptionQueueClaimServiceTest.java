package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.QueueClaimExitActor;
import com.example.hms.enums.QueueClaimReleaseReason;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.PrescriptionQueueClaim;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.WorkQueueClaimDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PrescriptionQueueClaimRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.WebRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * G13: the work-queue claim service, against a fixed clock. Every audit is
 * written through {@code TransactionCallbacks.afterCommit}; with no
 * transaction on the thread it runs at once, which is what most tests here
 * rely on, and {@link AfterCommit} proves the deferral itself.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PrescriptionQueueClaimService (G13)")
class PrescriptionQueueClaimServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 10, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    /** Distinctive names: no audit description may carry them (AC-15). */
    private static final String MEDICATION = "Zyloprimexol 300 mg";
    private static final String PATIENT_NAME = "Ousmanekarim";
    private static final String HOLDER_FIRST = "Fatoumatabintou";
    private static final String CALLER_FIRST = "Issiakadramane";

    @Mock private PrescriptionQueueClaimRepository claimRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private DispenseRepository dispenseRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditEventLogService;

    private PrescriptionQueueClaimService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID prescriptionId = UUID.randomUUID();
    private Prescription prescription;
    private User caller;
    private User holder;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        service = new PrescriptionQueueClaimService(claimRepository, prescriptionRepository, dispenseRepository,
                userRepository, roleValidator, auditEventLogService, CLOCK);
        prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescription.setMedicationName(MEDICATION);
        com.example.hms.model.Patient patient = new com.example.hms.model.Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName(PATIENT_NAME);
        prescription.setPatient(patient);
        caller = user(CALLER_FIRST, "Traore");
        holder = user(HOLDER_FIRST, "Kone");
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static User user(String first, String last) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    private PrescriptionQueueClaim claimBy(User who, LocalDateTime at) {
        PrescriptionQueueClaim claim = PrescriptionQueueClaim.builder()
                .prescription(prescription).claimedBy(who).claimedAt(at).build();
        claim.setId(UUID.randomUUID());
        return claim;
    }

    /** The scoped lock, an empty open-preparation check, and the caller. */
    private void inScopeAsCaller() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findByIdAndHospitalIdForUpdate(prescriptionId, hospitalId))
                .thenReturn(Optional.of(prescription));
        org.mockito.Mockito.lenient().when(roleValidator.getCurrentUserId()).thenReturn(caller.getId());
    }

    private void claimable() {
        inScopeAsCaller();
        when(dispenseRepository.existsByPrescription_IdAndStatus(prescriptionId, DispenseStatus.PENDING))
                .thenReturn(false);
        when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
    }

    private List<AuditEventRequestDTO> audits() {
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, org.mockito.Mockito.atLeast(0)).logEvent(captor.capture());
        return captor.getAllValues();
    }

    private List<AuditEventType> auditTypes() {
        return audits().stream().map(AuditEventRequestDTO::getEventType).toList();
    }

    @Nested
    @DisplayName("claim")
    class Claim {

        @Test
        @DisplayName("AC-1: an unclaimed row is claimed under the scoped lock, by the caller, at the clock's time")
        void claimsAnUnclaimedRow() {
            claimable();
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.empty());
            when(claimRepository.save(any(PrescriptionQueueClaim.class))).thenAnswer(inv -> inv.getArgument(0));

            WorkQueueClaimDTO dto = service.claim(prescriptionId);

            ArgumentCaptor<PrescriptionQueueClaim> saved = ArgumentCaptor.forClass(PrescriptionQueueClaim.class);
            verify(claimRepository).save(saved.capture());
            assertThat(saved.getValue().getClaimedBy()).isSameAs(caller);
            assertThat(saved.getValue().getClaimedAt()).isEqualTo(NOW);
            assertThat(saved.getValue().getPrescription()).isSameAs(prescription);
            verify(prescriptionRepository, never()).findById(any());
            assertThat(dto.getClaimedByUserId()).isEqualTo(caller.getId());
            assertThat(dto.getClaimedByName()).isEqualTo(CALLER_FIRST + " Traore");
            assertThat(dto.getClaimedAt()).isEqualTo(NOW);
            assertThat(dto.getExpiresAt()).isEqualTo(NOW.plusMinutes(15));
            assertThat(dto.isMine()).isTrue();
            assertThat(dto.getRenewed()).isFalse();

            List<AuditEventRequestDTO> audits = audits();
            assertThat(audits).hasSize(1);
            AuditEventRequestDTO audit = audits.getFirst();
            assertThat(audit.getEventType()).isEqualTo(AuditEventType.PRESCRIPTION_QUEUE_CLAIMED);
            assertThat(audit.getUserId()).isEqualTo(caller.getId());
            assertThat(audit.getResourceId()).isEqualTo(prescriptionId.toString());
            assertThat(audit.getEntityType()).isEqualTo("PRESCRIPTION");
        }

        @Test
        @DisplayName("AC-3: a plain claim over a colleague's active claim is 409 heldByOther; nothing saved, nothing audited")
        void heldByOtherIsRefused() {
            claimable();
            when(claimRepository.findByPrescription_Id(prescriptionId))
                    .thenReturn(Optional.of(claimBy(holder, NOW.minusMinutes(5))));

            assertThatThrownBy(() -> service.claim(prescriptionId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(MessageUtil.resolve("workqueue.claim.heldByOther"));
            verify(claimRepository, never()).save(any());
            verify(claimRepository, never()).delete(any());
            verifyNoInteractions(auditEventLogService);
        }

        @Test
        @DisplayName("AC-4: the holder's re-claim renews claimedAt in place and audits nothing")
        void holderRenews() {
            claimable();
            PrescriptionQueueClaim mine = claimBy(caller, NOW.minusMinutes(10));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(mine));
            when(claimRepository.save(mine)).thenReturn(mine);

            WorkQueueClaimDTO dto = service.claim(prescriptionId);

            assertThat(mine.getClaimedAt()).isEqualTo(NOW);
            assertThat(dto.getRenewed()).isTrue();
            assertThat(dto.isMine()).isTrue();
            verify(claimRepository, never()).delete(any());
            verifyNoInteractions(auditEventLogService);
        }

        @Test
        @DisplayName("AC-7: a claim made 16 minutes ago has lapsed: a plain claim takes the row in place, EXPIRED then CLAIMED")
        void expiredClaimIsReplacedInPlace() {
            claimable();
            PrescriptionQueueClaim stale = claimBy(holder, NOW.minusMinutes(16));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(stale));
            when(claimRepository.save(stale)).thenReturn(stale);

            WorkQueueClaimDTO dto = service.claim(prescriptionId);

            assertThat(stale.getClaimedBy()).isSameAs(caller);
            assertThat(stale.getClaimedAt()).isEqualTo(NOW);
            assertThat(dto.getRenewed()).isFalse();
            verify(claimRepository, never()).delete(any());
            List<AuditEventRequestDTO> audits = audits();
            assertThat(audits).extracting(AuditEventRequestDTO::getEventType).containsExactly(
                    AuditEventType.PRESCRIPTION_QUEUE_CLAIM_EXPIRED, AuditEventType.PRESCRIPTION_QUEUE_CLAIMED);
            assertThat(audits.getFirst().getEventDescription())
                    .contains(holder.getId().toString())
                    .contains(NOW.minusMinutes(16).toString());
            assertThat(audits.getFirst().getUserId()).isEqualTo(caller.getId());
        }

        @Test
        @DisplayName("AC-7: a claim made 14 minutes ago is still active")
        void fourteenMinutesIsActive() {
            claimable();
            when(claimRepository.findByPrescription_Id(prescriptionId))
                    .thenReturn(Optional.of(claimBy(holder, NOW.minusMinutes(14))));

            assertThatThrownBy(() -> service.claim(prescriptionId)).isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("AC-7: the TTL is read at each call, not stored per row: shortening it lapses an existing claim at once")
        void ttlChangeAppliesAtOnce() {
            claimable();
            PrescriptionQueueClaim claim = claimBy(holder, NOW.minusMinutes(10));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(claim));
            when(claimRepository.save(claim)).thenReturn(claim);
            ReflectionTestUtils.setField(service, "ttl", Duration.ofMinutes(5));

            WorkQueueClaimDTO dto = service.claim(prescriptionId);

            assertThat(dto.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
            assertThat(auditTypes()).containsExactly(
                    AuditEventType.PRESCRIPTION_QUEUE_CLAIM_EXPIRED, AuditEventType.PRESCRIPTION_QUEUE_CLAIMED);
        }

        @Test
        @DisplayName("AC-10: an open preparation is 409 dispense.ready.openPreparation, checked before the claim")
        void openPreparationIsRefused() {
            inScopeAsCaller();
            when(dispenseRepository.existsByPrescription_IdAndStatus(prescriptionId, DispenseStatus.PENDING))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.claim(prescriptionId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(MessageUtil.resolve("dispense.ready.openPreparation"));
            verify(claimRepository, never()).findByPrescription_Id(any());
            verifyNoInteractions(auditEventLogService);
        }

        @Test
        @DisplayName("AC-11: a status outside the queue (withdrawn, dispensed, clarification) is 409 notInQueue")
        void notInQueueIsRefused() {
            for (PrescriptionStatus status : List.of(PrescriptionStatus.CANCELLED, PrescriptionStatus.DISCONTINUED,
                    PrescriptionStatus.DISPENSED, PrescriptionStatus.PENDING_CLARIFICATION,
                    PrescriptionStatus.SENT_TO_PARTNER)) {
                prescription.setStatus(status);
                when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
                when(prescriptionRepository.findByIdAndHospitalIdForUpdate(prescriptionId, hospitalId))
                        .thenReturn(Optional.of(prescription));
                assertThatThrownBy(() -> service.claim(prescriptionId))
                        .as(status.name())
                        .isInstanceOf(ConflictException.class)
                        .hasMessage(MessageUtil.resolve("workqueue.claim.notInQueue"));
                assertThatThrownBy(() -> service.takeOver(prescriptionId))
                        .as(status.name())
                        .isInstanceOf(ConflictException.class);
            }
            verify(claimRepository, never()).save(any());
            verifyNoInteractions(auditEventLogService);
        }

        @Test
        @DisplayName("AC-12: a TRANSMISSION_FAILED row (and a PARTNER_ACCEPTED one) can be claimed")
        void transmissionFailedIsClaimable() {
            for (PrescriptionStatus status : List.of(PrescriptionStatus.TRANSMISSION_FAILED,
                    PrescriptionStatus.PARTNER_ACCEPTED)) {
                prescription.setStatus(status);
                claimable();
                when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.empty());
                when(claimRepository.save(any(PrescriptionQueueClaim.class))).thenAnswer(inv -> inv.getArgument(0));

                assertThat(service.claim(prescriptionId).isMine()).as(status.name()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("take-over")
    class TakeOver {

        @Test
        @DisplayName("AC-6: the same row moves to the caller in place, TAKEN_OVER with the previous holder and claimedAt")
        void takesOverInPlace() {
            claimable();
            LocalDateTime previous = NOW.minusMinutes(5);
            PrescriptionQueueClaim claim = claimBy(holder, previous);
            UUID rowId = claim.getId();
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(claim));
            when(claimRepository.save(claim)).thenReturn(claim);

            WorkQueueClaimDTO dto = service.takeOver(prescriptionId);

            assertThat(claim.getId()).isEqualTo(rowId);
            assertThat(claim.getClaimedBy()).isSameAs(caller);
            assertThat(claim.getClaimedAt()).isEqualTo(NOW);
            verify(claimRepository, never()).delete(any());
            assertThat(dto.isMine()).isTrue();
            assertThat(dto.getRenewed()).isFalse();
            List<AuditEventRequestDTO> audits = audits();
            assertThat(audits).extracting(AuditEventRequestDTO::getEventType)
                    .containsExactly(AuditEventType.PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER);
            assertThat(audits.getFirst().getUserId()).isEqualTo(caller.getId());
            assertThat(audits.getFirst().getEventDescription())
                    .contains(holder.getId().toString())
                    .contains(previous.toString());
        }

        @Test
        @DisplayName("AC-6: a take-over with nobody holding the row is a plain claim")
        void takeOverOfNothingIsAClaim() {
            claimable();
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.empty());
            when(claimRepository.save(any(PrescriptionQueueClaim.class))).thenAnswer(inv -> inv.getArgument(0));

            service.takeOver(prescriptionId);

            assertThat(auditTypes()).containsExactly(AuditEventType.PRESCRIPTION_QUEUE_CLAIMED);
        }

        @Test
        @DisplayName("AC-6: a take-over of one's own claim renews it")
        void takeOverOfOwnClaimRenews() {
            claimable();
            PrescriptionQueueClaim mine = claimBy(caller, NOW.minusMinutes(3));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(mine));
            when(claimRepository.save(mine)).thenReturn(mine);

            assertThat(service.takeOver(prescriptionId).getRenewed()).isTrue();
            verifyNoInteractions(auditEventLogService);
        }

        @Test
        @DisplayName("AC-10: a take-over of a prepared row is 409 dispense.ready.openPreparation")
        void takeOverOfPreparedRowIsRefused() {
            inScopeAsCaller();
            when(dispenseRepository.existsByPrescription_IdAndStatus(prescriptionId, DispenseStatus.PENDING))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.takeOver(prescriptionId)).isInstanceOf(ConflictException.class)
                    .hasMessage(MessageUtil.resolve("dispense.ready.openPreparation"));
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("AC-5: the holder's release deletes the row and audits RELEASED")
        void holderReleases() {
            inScopeAsCaller();
            PrescriptionQueueClaim mine = claimBy(caller, NOW.minusMinutes(2));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(mine));

            service.release(prescriptionId);

            verify(claimRepository).delete(mine);
            List<AuditEventRequestDTO> audits = audits();
            assertThat(audits).extracting(AuditEventRequestDTO::getEventType)
                    .containsExactly(AuditEventType.PRESCRIPTION_QUEUE_CLAIM_RELEASED);
            assertThat(audits.getFirst().getEventDescription()).contains("RELEASED");
        }

        @Test
        @DisplayName("AC-5: nothing to release answers normally and audits nothing")
        void nothingToRelease() {
            inScopeAsCaller();
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.empty());

            service.release(prescriptionId);

            verify(claimRepository, never()).delete(any());
            verifyNoInteractions(auditEventLogService);
        }

        @Test
        @DisplayName("AC-5: a colleague's active claim is 409 notHolder; nothing changes")
        void nonHolderIsRefused() {
            inScopeAsCaller();
            when(claimRepository.findByPrescription_Id(prescriptionId))
                    .thenReturn(Optional.of(claimBy(holder, NOW.minusMinutes(1))));

            assertThatThrownBy(() -> service.release(prescriptionId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(MessageUtil.resolve("workqueue.claim.notHolder"));
            verify(claimRepository, never()).delete(any());
            verifyNoInteractions(auditEventLogService);
        }

        @Test
        @DisplayName("AC-5/AC-7: an expired claim is deleted and audited EXPIRED, whoever releases it")
        void expiredIsDeletedAsExpired() {
            inScopeAsCaller();
            PrescriptionQueueClaim stale = claimBy(holder, NOW.minusMinutes(20));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(stale));

            service.release(prescriptionId);

            verify(claimRepository).delete(stale);
            assertThat(auditTypes()).containsExactly(AuditEventType.PRESCRIPTION_QUEUE_CLAIM_EXPIRED);
        }

        @Test
        @DisplayName("release does not check the status: ending a claim on a withdrawn order is allowed")
        void releaseIgnoresStatus() {
            prescription.setStatus(PrescriptionStatus.CANCELLED);
            inScopeAsCaller();
            PrescriptionQueueClaim mine = claimBy(caller, NOW.minusMinutes(2));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(mine));

            service.release(prescriptionId);

            verify(claimRepository).delete(mine);
        }
    }

    @Nested
    @DisplayName("releaseOnExit (plan rule 4)")
    class ReleaseOnExit {

        private final UUID actorId = UUID.randomUUID();

        @Test
        @DisplayName("AC-8: a queue-role colleague acting over an active claim: deleted, TAKEN_OVER with the reason, never refused")
        void colleagueTakesOver() {
            PrescriptionQueueClaim claim = claimBy(holder, NOW.minusMinutes(5));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(claim));

            service.releaseOnExit(prescription, QueueClaimReleaseReason.DISPENSED, actorId,
                    QueueClaimExitActor.QUEUE_ROLE);

            verify(claimRepository).delete(claim);
            List<AuditEventRequestDTO> audits = audits();
            assertThat(audits).extracting(AuditEventRequestDTO::getEventType)
                    .containsExactly(AuditEventType.PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER);
            assertThat(audits.getFirst().getUserId()).isEqualTo(actorId);
            assertThat(audits.getFirst().getEventDescription())
                    .contains("by DISPENSED").contains(holder.getId().toString());
        }

        @Test
        @DisplayName("AC-9: the holder's own action: RELEASED with the reason")
        void holderReleases() {
            PrescriptionQueueClaim claim = claimBy(holder, NOW.minusMinutes(5));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(claim));

            service.releaseOnExit(prescription, QueueClaimReleaseReason.PREPARED, holder.getId(),
                    QueueClaimExitActor.QUEUE_ROLE);

            verify(claimRepository).delete(claim);
            List<AuditEventRequestDTO> audits = audits();
            assertThat(audits).extracting(AuditEventRequestDTO::getEventType)
                    .containsExactly(AuditEventType.PRESCRIPTION_QUEUE_CLAIM_RELEASED);
            assertThat(audits.getFirst().getEventDescription()).contains("PREPARED");
        }

        @Test
        @DisplayName("AC-12: a non-queue actor (a DOCTOR's dispatch) over a colleague's claim: RELEASED DISPATCHED")
        void otherActorReleases() {
            PrescriptionQueueClaim claim = claimBy(holder, NOW.minusMinutes(5));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(claim));

            service.releaseOnExit(prescription, QueueClaimReleaseReason.DISPATCHED, actorId,
                    QueueClaimExitActor.OTHER);

            assertThat(auditTypes()).containsExactly(AuditEventType.PRESCRIPTION_QUEUE_CLAIM_RELEASED);
        }

        @Test
        @DisplayName("AC-11: WITHDRAWN and CHANGED are RELEASED whoever acts, even a queue role that is not the holder")
        void prescriberReasonsAlwaysRelease() {
            for (QueueClaimReleaseReason reason : List.of(QueueClaimReleaseReason.WITHDRAWN,
                    QueueClaimReleaseReason.CHANGED)) {
                when(claimRepository.findByPrescription_Id(prescriptionId))
                        .thenReturn(Optional.of(claimBy(holder, NOW.minusMinutes(5))));
                service.releaseOnExit(prescription, reason, actorId, QueueClaimExitActor.QUEUE_ROLE);
            }
            assertThat(auditTypes()).containsExactly(AuditEventType.PRESCRIPTION_QUEUE_CLAIM_RELEASED,
                    AuditEventType.PRESCRIPTION_QUEUE_CLAIM_RELEASED);
        }

        @Test
        @DisplayName("AC-7: an expired claim found by an exit write is deleted and audited EXPIRED")
        void expiredIsExpired() {
            PrescriptionQueueClaim stale = claimBy(holder, NOW.minusMinutes(30));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(stale));

            service.releaseOnExit(prescription, QueueClaimReleaseReason.ROUTED, actorId,
                    QueueClaimExitActor.QUEUE_ROLE);

            verify(claimRepository).delete(stale);
            assertThat(auditTypes()).containsExactly(AuditEventType.PRESCRIPTION_QUEUE_CLAIM_EXPIRED);
        }

        @Test
        @DisplayName("no claim: nothing deleted, nothing audited, no scope call")
        void noClaim() {
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.empty());

            service.releaseOnExit(prescription, QueueClaimReleaseReason.DISPENSED, actorId,
                    QueueClaimExitActor.QUEUE_ROLE);

            verify(claimRepository, never()).delete(any());
            verifyNoInteractions(auditEventLogService, roleValidator, prescriptionRepository);
        }

        @Test
        @DisplayName("AC-16: the exit release runs with the flag off")
        void runsWithTheFlagOff() {
            ReflectionTestUtils.setField(service, "enabled", false);
            PrescriptionQueueClaim claim = claimBy(holder, NOW.minusMinutes(5));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(claim));

            service.releaseOnExit(prescription, QueueClaimReleaseReason.DISPENSED, holder.getId(),
                    QueueClaimExitActor.QUEUE_ROLE);

            verify(claimRepository).delete(claim);
        }
    }

    @Nested
    @DisplayName("AC-9: audits are written after commit")
    class AfterCommit {

        @AfterEach
        void clearSynchronization() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("with a transaction on the thread, nothing is audited until afterCommit runs; a rollback audits nothing")
        void deferredUntilCommit() {
            PrescriptionQueueClaim claim = claimBy(holder, NOW.minusMinutes(5));
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(claim));
            TransactionSynchronizationManager.initSynchronization();

            service.releaseOnExit(prescription, QueueClaimReleaseReason.DISPENSED, holder.getId(),
                    QueueClaimExitActor.QUEUE_ROLE);

            verifyNoInteractions(auditEventLogService);
            List<TransactionSynchronization> registered =
                    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
            assertThat(registered).hasSize(1);

            // A rollback: completion without commit writes nothing.
            registered.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(auditEventLogService);

            // A commit writes it, once.
            registered.forEach(TransactionSynchronization::afterCommit);
            verify(auditEventLogService, times(1)).logEvent(any());
        }

        @Test
        @DisplayName("an audit failure after commit is swallowed")
        void auditFailureIsSwallowed() {
            claimable();
            when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.empty());
            when(claimRepository.save(any(PrescriptionQueueClaim.class))).thenAnswer(inv -> inv.getArgument(0));
            when(auditEventLogService.logEvent(any())).thenThrow(new IllegalStateException("audit down"));

            assertThat(service.claim(prescriptionId).isMine()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-14 / AC-16: scope and the flag")
    class ScopeAndFlag {

        private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

        @SuppressWarnings("unchecked")
        private Map<String, Object> bodyOf(Runnable call) {
            WebRequest request = mock(WebRequest.class);
            when(request.getDescription(false)).thenReturn("uri=/api/pharmacy/dispense/work-queue/x/claim");
            try {
                call.run();
            } catch (ResourceNotFoundException ex) {
                Map<String, Object> body = new HashMap<>(
                        (Map<String, Object>) handler.handleResourceNotFoundException(ex, request).getBody());
                body.remove("timestamp");
                body.remove("path");
                return body;
            }
            throw new AssertionError("expected a 404");
        }

        private List<Map<String, Object>> threeRefusals(Runnable call) {
            // a random id: nothing at this hospital
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(prescriptionId, hospitalId))
                    .thenReturn(Optional.empty());
            Map<String, Object> randomId = bodyOf(call);
            // another hospital's prescription: the scoped lock finds nothing either
            UUID otherHospital = UUID.randomUUID();
            when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospital);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(prescriptionId, otherHospital))
                    .thenReturn(Optional.empty());
            Map<String, Object> foreign = bodyOf(call);
            // a null scope (global view): refused before any query
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            Map<String, Object> nullScope = bodyOf(call);
            return List.of(randomId, foreign, nullScope);
        }

        @Test
        @DisplayName("claim, take-over and release: unknown, foreign and null-scope ids give one identical 404")
        void identical404s() {
            for (Runnable call : List.<Runnable>of(
                    () -> service.claim(prescriptionId),
                    () -> service.takeOver(prescriptionId),
                    () -> service.release(prescriptionId))) {
                List<Map<String, Object>> bodies = threeRefusals(call);
                assertThat(bodies.get(0)).containsEntry("status", 404)
                        .containsEntry("message", MessageUtil.resolve("prescription.notfound"));
                assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
                assertThat(bodies.get(2)).isEqualTo(bodies.get(0));
            }
            verify(prescriptionRepository, never()).findByIdForUpdate(any());
            verify(prescriptionRepository, never()).findById(any());
            verifyNoInteractions(claimRepository, auditEventLogService);
        }

        @Test
        @DisplayName("AC-16: with the flag off the three endpoints answer 404 workqueue.claim.disabled before any lock")
        void flagOff() {
            ReflectionTestUtils.setField(service, "enabled", false);
            for (Runnable call : List.<Runnable>of(
                    () -> service.claim(prescriptionId),
                    () -> service.takeOver(prescriptionId),
                    () -> service.release(prescriptionId))) {
                assertThatThrownBy(call::run).isInstanceOf(ResourceNotFoundException.class)
                        .hasMessage(MessageUtil.resolve("workqueue.claim.disabled"));
            }
            verifyNoInteractions(prescriptionRepository, claimRepository, auditEventLogService);
            assertThat(service.activeClaimsFor(List.of(prescription))).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC-2: activeClaimsFor")
    class ActiveClaims {

        @Test
        @DisplayName("one batched query; only active claims; mine only for the caller")
        void decoratesTheActiveClaims() {
            Prescription other = new Prescription();
            other.setId(UUID.randomUUID());
            Prescription stale = new Prescription();
            stale.setId(UUID.randomUUID());
            PrescriptionQueueClaim mine = claimBy(caller, NOW.minusMinutes(1));
            PrescriptionQueueClaim theirs = PrescriptionQueueClaim.builder()
                    .prescription(other).claimedBy(holder).claimedAt(NOW.minusMinutes(2)).build();
            PrescriptionQueueClaim lapsed = PrescriptionQueueClaim.builder()
                    .prescription(stale).claimedBy(holder).claimedAt(NOW.minusMinutes(16)).build();
            when(roleValidator.getCurrentUserId()).thenReturn(caller.getId());
            when(claimRepository.findByPrescription_IdIn(any())).thenReturn(List.of(mine, theirs, lapsed));

            Map<UUID, WorkQueueClaimDTO> claims = service.activeClaimsFor(List.of(prescription, other, stale));

            verify(claimRepository, times(1)).findByPrescription_IdIn(any());
            assertThat(claims).containsOnlyKeys(prescriptionId, other.getId());
            assertThat(claims.get(prescriptionId).isMine()).isTrue();
            assertThat(claims.get(other.getId()).isMine()).isFalse();
            assertThat(claims.get(other.getId()).getClaimedByName()).isEqualTo(HOLDER_FIRST + " Kone");
            assertThat(claims.get(other.getId()).getRenewed()).isNull();
        }
    }

    @Test
    @DisplayName("AC-15: no audit description carries a medication, patient or staff name")
    void descriptionsCarryNoNames() {
        // every audit path at once: claim over an expired row, a take-over, a release, exits
        claimable();
        PrescriptionQueueClaim claim = claimBy(holder, NOW.minusMinutes(20));
        when(claimRepository.findByPrescription_Id(prescriptionId)).thenReturn(Optional.of(claim));
        when(claimRepository.save(claim)).thenReturn(claim);
        service.claim(prescriptionId);
        claim.setClaimedBy(holder);
        claim.setClaimedAt(NOW.minusMinutes(1));
        service.takeOver(prescriptionId);
        service.release(prescriptionId);
        claim.setClaimedBy(holder);
        service.releaseOnExit(prescription, QueueClaimReleaseReason.DISPENSED, caller.getId(),
                QueueClaimExitActor.QUEUE_ROLE);
        service.releaseOnExit(prescription, QueueClaimReleaseReason.WITHDRAWN, caller.getId(),
                QueueClaimExitActor.OTHER);

        List<AuditEventRequestDTO> audits = audits();
        assertThat(audits).extracting(AuditEventRequestDTO::getEventType).contains(
                AuditEventType.PRESCRIPTION_QUEUE_CLAIMED, AuditEventType.PRESCRIPTION_QUEUE_CLAIM_EXPIRED,
                AuditEventType.PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER, AuditEventType.PRESCRIPTION_QUEUE_CLAIM_RELEASED);
        for (AuditEventRequestDTO audit : audits) {
            assertThat(audit.getEventDescription())
                    .doesNotContain(MEDICATION).doesNotContain("Zyloprimexol")
                    .doesNotContain(PATIENT_NAME)
                    .doesNotContain(HOLDER_FIRST).doesNotContain("Kone")
                    .doesNotContain(CALLER_FIRST).doesNotContain("Traore");
            assertThat(audit.getUserName()).isNull();
            assertThat(audit.getResourceName()).isNull();
        }
    }
}
