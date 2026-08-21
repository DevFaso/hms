package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Notification;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.repository.LabResultRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CriticalValueNotificationServiceTest {

    @Mock private NotificationService notificationService;
    @Mock private SmsService smsService;
    @Mock private LabResultRepository labResultRepository;
    @Mock private LabResultMapper labResultMapper;
    @Mock private com.example.hms.repository.StaffRepository staffRepository;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @InjectMocks private CriticalValueNotificationService service;

    private LabResult result;
    private User orderingUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "escalateAfterMinutes", 30L);

        orderingUser = new User();
        orderingUser.setId(UUID.randomUUID());
        orderingUser.setUsername("dr.diallo");
        orderingUser.setPhoneNumber("+22670707070");

        Staff orderingStaff = Staff.builder().build();
        orderingStaff.setUser(orderingUser);

        Patient patient = new Patient();
        patient.setFirstName("Bea");
        patient.setLastName("Ward");

        LabTestDefinition definition = new LabTestDefinition();
        definition.setName("Potassium");

        LabOrder order = new LabOrder();
        order.setOrderingStaff(orderingStaff);
        order.setPatient(patient);
        order.setLabTestDefinition(definition);

        result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue("7.1");
        result.setResultUnit("mmol/L");

        lenient().when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenReturn(new Notification());
        lenient().when(smsService.deliversRealSms()).thenReturn(false);
    }

    @Test
    void notifiesOrderingProviderForHl7CriticalFlag() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);

        service.notifyIfCritical(result);

        verify(notificationService).createNotification(
            contains("Potassium = 7.1 mmol/L"), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT"));
        assertThat(result.getCriticalNotifiedAt()).isNotNull();
        verify(labResultRepository).save(result);
    }

    @Test
    void notifiesForComputedHighSeverity() {
        when(labResultMapper.toResponseDTO(result))
            .thenReturn(LabResultResponseDTO.builder().severityFlag("HIGH").build());

        service.notifyIfCritical(result);

        verify(notificationService).createNotification(anyString(), eq("dr.diallo"), anyString());
    }

    @Test
    void skipsNormalResults() {
        when(labResultMapper.toResponseDTO(result))
            .thenReturn(LabResultResponseDTO.builder().severityFlag("NORMAL").build());

        service.notifyIfCritical(result);

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
        assertThat(result.getCriticalNotifiedAt()).isNull();
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void doesNotRenotifyAlreadyNotifiedResults() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(5));

        service.notifyIfCritical(result);

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void notificationFailureNeverPropagates() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("broker down"));

        service.notifyIfCritical(result); // must not throw
    }

    @Test
    void smsStaysOffWhileTransportIsMock() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        when(smsService.deliversRealSms()).thenReturn(false);

        service.notifyIfCritical(result);

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    void smsSentOverRealTransport() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        when(smsService.deliversRealSms()).thenReturn(true);

        service.notifyIfCritical(result);

        verify(smsService).send(eq("+22670707070"), contains("Potassium"));
    }

    @Test
    void stampsWithoutNotifyingWhenNoOrderingUser() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.getLabOrder().setOrderingStaff(null); // SYSTEM-actor path

        service.notifyIfCritical(result);

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
        assertThat(result.getCriticalNotifiedAt()).isNotNull(); // sweep convergence
        verify(labResultRepository).save(result);
    }

    @Test
    void escalateOverdueNotifiesAndStampsOnce() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(result));

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        verify(notificationService).createNotification(
            contains("ESCALATION"), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT_ESCALATION"));
        assertThat(result.getCriticalEscalatedAt()).isNotNull();
        verify(labResultRepository).save(result);
    }

    @Test
    void escalateOverdueStampsEvenWithoutResolvableUser() {
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        result.getLabOrder().setOrderingStaff(null);
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(result));

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        assertThat(result.getCriticalEscalatedAt()).isNotNull();
        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void escalateOverdueSkipsFailingRowAndContinues() {
        LabResult broken = new LabResult();
        broken.setId(UUID.randomUUID());
        broken.setLabOrder(result.getLabOrder());
        broken.setResultValue("x");
        broken.setCriticalNotifiedAt(LocalDateTime.now().minusHours(2));
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));

        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(broken, result));
        doThrow(new IllegalStateException("boom")).when(labResultRepository).save(broken);

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1); // broken row skipped, second processed
        verify(labResultRepository).save(result);
    }

    // ── The escalation is a CHAIN and it REPEATS (P0 #5) ───────────────────
    // Both were previously untrue. escalateOverdue re-notified the SAME
    // ordering provider who had already ignored the first alert, then stamped
    // criticalEscalatedAt — which the sweep query excluded on. So a critical
    // result nobody acknowledged produced two notifications to one person and
    // then went permanently quiet.

    private UUID givenHospital() {
        UUID hospitalId = UUID.randomUUID();
        com.example.hms.model.Hospital hospital =
            com.example.hms.model.Hospital.builder().name("CHU").code("CHU").build();
        hospital.setId(hospitalId);
        result.getLabOrder().setHospital(hospital);
        return hospitalId;
    }

    @Test
    void secondRoundWidensBeyondTheProviderWhoAlreadyIgnoredIt() {
        UUID hospitalId = givenHospital();
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(2));
        result.setCriticalEscalationLevel((short) 1);
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_HOSPITAL_ADMIN"))
            .thenReturn(List.of("admin.kabore", "admin.sawadogo"));
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(result));

        service.escalateOverdue();

        // The provider stays on the list — they can still act clinically — but
        // they are no longer the ONLY person told.
        verify(notificationService).createNotification(
            anyString(), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT_ESCALATION"));
        verify(notificationService).createNotification(
            anyString(), eq("admin.kabore"), eq("CRITICAL_LAB_RESULT_ESCALATION"));
        verify(notificationService).createNotification(
            anyString(), eq("admin.sawadogo"), eq("CRITICAL_LAB_RESULT_ESCALATION"));
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 2);
    }

    @Test
    void firstRoundIsStillJustANudgeToTheProvider() {
        givenHospital();
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(result));

        service.escalateOverdue();

        // Round 1: they may simply not have looked yet. No point waking the
        // hospital's admins for that.
        verify(staffRepository, never()).findActiveUsernamesByHospitalAndRole(any(), anyString());
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 1);
    }

    @Test
    void keepsEscalatingRatherThanGoingSilent() {
        UUID hospitalId = givenHospital();
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(9));
        result.setCriticalEscalationLevel((short) 8);
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_HOSPITAL_ADMIN"))
            .thenReturn(List.of("admin.kabore"));
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(result));

        service.escalateOverdue();

        // There is deliberately no round cap. Going quiet on an unacknowledged
        // critical value is the exact failure this service exists to prevent, so
        // a cap would reintroduce it with extra steps.
        verify(notificationService).createNotification(
            anyString(), eq("admin.kabore"), eq("CRITICAL_LAB_RESULT_ESCALATION"));
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 9);
    }

    // ── Read-back (P0 #5) ─────────────────────────────────────────────────
    // Never built. The acknowledge endpoint takes no body, so nothing recorded
    // WHAT the clinician was told — the javadoc simply asserted that clicking
    // acknowledge counted as a read-back.

    @Test
    void matchingReadBackResolvesTheResultAndStopsEscalation() {
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(i -> i.getArgument(0));
        UUID actor = UUID.randomUUID();

        LabResult updated = service.recordReadBack(result, "7.1", actor, "Dr Diallo");

        assertThat(updated.getCriticalReadBackAt()).isNotNull();
        assertThat(updated.getCriticalReadBackValue()).isEqualTo("7.1");
        assertThat(updated.getCriticalReadBackByDisplay()).isEqualTo("Dr Diallo");
        assertThat(updated.isAcknowledged()).isTrue();
    }

    @Test
    void mismatchedReadBackIsRejectedButStillRecorded() {
        // The mismatch is persisted in a REQUIRES_NEW transaction onto a row
        // RELOADED inside it — never the caller's managed entity, whose
        // transaction the BusinessException is about to roll back. The previous
        // version of this test asserted on the in-memory entity against a
        // mocked repository, which is exactly how the rollback that erased
        // every mismatch record went unnoticed.
        LabResult reloaded = new LabResult();
        reloaded.setId(result.getId());
        reloaded.setResultValue("7.1");
        when(labResultRepository.findById(result.getId())).thenReturn(java.util.Optional.of(reloaded));
        UUID actor = UUID.randomUUID();

        // 1.7 instead of 7.1 — a transposition, which is the error a read-back
        // exists to catch and the reason it is worth storing the wrong value.
        assertThatThrownBy(() -> service.recordReadBack(result, "1.7", actor, "Dr Diallo"))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);

        // The inner-transaction row carries the mismatch and was saved…
        assertThat(reloaded.getCriticalReadBackValue()).isEqualTo("1.7");
        assertThat(reloaded.getCriticalReadBackByUserId()).isEqualTo(actor);
        assertThat(reloaded.getCriticalReadBackByDisplay()).isEqualTo("Dr Diallo");
        verify(labResultRepository).save(reloaded);
        // …and it went through the separate transaction, not the doomed one.
        verify(transactionManager).getTransaction(any());

        // Nothing on either row claims the read-back succeeded.
        assertThat(reloaded.getCriticalReadBackAt()).isNull();
        assertThat(reloaded.isAcknowledged()).isFalse();
        assertThat(result.getCriticalReadBackAt()).isNull();
        assertThat(result.isAcknowledged()).isFalse();
    }

    @Test
    void readBackToleratesTrailingZeroFormatting() {
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(i -> i.getArgument(0));

        // A clinician repeating the number correctly must not be told they got
        // it wrong because the lab wrote it with a trailing zero.
        LabResult updated = service.recordReadBack(result, "7.10", UUID.randomUUID(), "Dr Diallo");

        assertThat(updated.getCriticalReadBackAt()).isNotNull();
        assertThat(updated.isAcknowledged()).isTrue();
    }
}
