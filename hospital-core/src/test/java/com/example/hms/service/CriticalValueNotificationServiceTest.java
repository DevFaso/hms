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
import com.example.hms.repository.LabResultRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Spy;
import org.springframework.context.MessageSource;
import com.example.hms.i18n.TestMessageSources;

@ExtendWith(MockitoExtension.class)
class CriticalValueNotificationServiceTest {

    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS) private com.example.hms.service.RenderingNotificationService notificationService;
    @Mock private SmsService smsService;
    @Mock private LabResultRepository labResultRepository;
    @Mock private LabResultMapper labResultMapper;
    @Mock private com.example.hms.repository.StaffRepository staffRepository;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Spy private MessageSource messageSource = TestMessageSources.bundles();

    @InjectMocks private CriticalValueNotificationService service;

    private LabResult result;
    private User orderingUser;

    @BeforeEach
    void setUp() {
        // The body asserts it runs under the lock; unit tests have no proxy.
        LockAssert.TestHelper.makeAllAssertsPass(true);
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
        // QA D709: only a released result pages anyone.
        result.setReleased(true);

        lenient().when(notificationService.created(anyString(), anyString(), anyString()))
            .thenReturn(new Notification());
        lenient().when(smsService.deliversRealSms()).thenReturn(false);
    }

    @Test
    void notifiesOrderingProviderForHl7CriticalFlag() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);

        service.notifyIfCritical(result);

        verify(notificationService).created(
            contains("Potassium 7,1 mmol/L"), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT"));
        assertThat(result.getCriticalNotifiedAt()).isNotNull();
        verify(labResultRepository).save(result);
    }

    @Test
    void notifiesForComputedHighSeverity() {
        when(labResultMapper.severityFlagOf(result)).thenReturn("HIGH");
        when(labResultMapper.raisesCriticalAlert("HIGH")).thenReturn(true);

        service.notifyIfCritical(result);

        verify(notificationService).created(anyString(), eq("dr.diallo"), anyString());
    }

    /** QA D709: the page went out on entry, before release, while GET answered 404. */
    @Test
    void anUnreleasedCriticalResultPagesNobodyAndIsNotStamped() {
        result.setReleased(false);
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);

        service.notifyIfCritical(result, "CRITICAL");

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
        assertThat(result.getCriticalNotifiedAt()).isNull();
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void skipsNormalResults() {
        when(labResultMapper.severityFlagOf(result)).thenReturn("NORMAL");

        service.notifyIfCritical(result);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(result.getCriticalNotifiedAt()).isNull();
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    /** The real grading rule behind the alert, ranges configured in {@code rangeUnit}. */
    private void gradeWithRealMapper(String rangeUnit) {
        ReflectionTestUtils.setField(service, "labResultMapper", new LabResultMapper());
        result.getLabOrder().getLabTestDefinition().setReferenceRanges(new java.util.ArrayList<>(List.of(
            com.example.hms.model.LabTestReferenceRange.builder().minValue(3.5).maxValue(5.0).unit(rangeUnit).build())));
    }

    @Test
    void aValueOutsideARangeInAnotherUnitRaisesNoAlert() {
        // 7.1 mmol/L against 3.5-5.0 mg/dL used to grade HIGH and page the
        // ordering doctor; limits in another unit grade nothing now.
        gradeWithRealMapper("mg/dL");

        service.notifyIfCritical(result);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(result.getCriticalNotifiedAt()).isNull();
    }

    /**
     * QA D313 (D206 follow-up): K 7.9 on a range that declares no critical
     * limits is out of range, HIGH, but not a panic value: no page, and every
     * screen labels it HIGH. The D107/D271 fail-safe paged it as critical.
     */
    @Test
    void aHighWithoutCriticalLimitsIsHighAndNotPaged() {
        gradeWithRealMapper("mmol/L");
        result.setResultValue("7.9");
        LabResultMapper mapper = new LabResultMapper();

        service.notifyIfCritical(result);

        assertThat(result.getCriticalNotifiedAt()).isNull();
        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(mapper.toResponseDTO(result).getSeverityFlag()).isEqualTo("HIGH");
        assertThat(mapper.severityFlagOf(result)).isEqualTo("HIGH");
    }

    /** QA D313: the same for a value below the range: LOW, not paged. */
    @Test
    void aLowWithoutCriticalLimitsIsLowAndNotPaged() {
        gradeWithRealMapper("mmol/L");
        result.setResultValue("2.0");
        LabResultMapper mapper = new LabResultMapper();

        service.notifyIfCritical(result);

        assertThat(result.getCriticalNotifiedAt()).isNull();
        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(mapper.severityFlagOf(result)).isEqualTo("LOW");
        assertThat(mapper.raisesCriticalAlert("LOW")).isFalse();
    }

    /** QA D271: once critical limits exist, a value below the range but above them stays LOW. */
    @Test
    void aLowInsideCriticalLimitsIsLabelledLowAndNotPaged() {
        gradeWithCriticalLimits(2.5, 6.5);
        result.setResultValue("3.0");

        service.notifyIfCritical(result);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(new LabResultMapper().severityFlagOf(result)).isEqualTo("LOW");
    }

    /** QA D206: once critical limits exist, a value inside them stays HIGH. */
    @Test
    void aHighInsideCriticalLimitsIsLabelledHigh() {
        gradeWithCriticalLimits(2.5, 8.5);
        result.setResultValue("7.9");

        assertThat(new LabResultMapper().severityFlagOf(result)).isEqualTo("HIGH");
    }

    /** D107: with critical limits set, HIGH is HIGH and only a value beyond them alerts. */
    private void gradeWithCriticalLimits(Double criticalLow, Double criticalHigh) {
        ReflectionTestUtils.setField(service, "labResultMapper", new LabResultMapper());
        result.getLabOrder().getLabTestDefinition().setReferenceRanges(new java.util.ArrayList<>(List.of(
            com.example.hms.model.LabTestReferenceRange.builder().minValue(3.5).maxValue(5.0).unit("mmol/L")
                .criticalLow(criticalLow).criticalHigh(criticalHigh).build())));
    }

    @Test
    void aHighValueInsideTheCriticalLimitsRaisesNoAlert() {
        gradeWithCriticalLimits(2.5, 7.5);
        result.setResultValue("7.1");

        service.notifyIfCritical(result);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
    }

    @Test
    void aValueBeyondTheCriticalHighAlerts() {
        gradeWithCriticalLimits(2.5, 6.5);
        result.setResultValue("7.1");

        service.notifyIfCritical(result);

        verify(notificationService).created(anyString(), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT"));
    }

    @Test
    void aValueBelowTheCriticalLowAlerts() {
        gradeWithCriticalLimits(2.5, 6.5);
        result.setResultValue("2.0");

        service.notifyIfCritical(result);

        verify(notificationService).created(anyString(), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT"));
    }

    @Test
    void aValueOutsideARangeWithoutCriticalLimitsDoesNotPage() {
        // QA D313: out of range is abnormal, not critical, until limits exist.
        gradeWithRealMapper("mmol/L");

        service.notifyIfCritical(result);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
    }

    @Test
    void aBareRangeOnATestInAnotherUnitRaisesNoAlert() {
        // The range states no unit, so it is in the test's unit (mg/dL).
        gradeWithRealMapper(null);
        result.getLabOrder().getLabTestDefinition().setUnit("mg/dL");

        service.notifyIfCritical(result);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
    }

    @Test
    void anAnalyserCriticalFlagStillAlertsWhenTheUnitsDiffer() {
        // OBX-8 is the laboratory's own grading; only the range-derived one is withheld.
        gradeWithRealMapper("mg/dL");
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);

        service.notifyIfCritical(result);

        verify(notificationService).created(anyString(), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT"));
    }

    @Test
    void doesNotRenotifyAlreadyNotifiedResults() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(5));

        service.notifyIfCritical(result);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
    }

    @Test
    void notificationFailurePropagatesRatherThanPoisoningTheTransaction() {
        // This runs in the caller's transaction, so a persistence failure
        // marks it rollback-only whatever this method does with the
        // exception: swallowing it left the caller believing the alert was
        // contained and then handed them a 500 at commit with the cause
        // logged as a warning. Failing here means the clinical write is
        // retried — recoverable — where a critical result nobody was told
        // about is not.
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        when(notificationService.created(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("broker down"));

        assertThatThrownBy(() -> service.notifyIfCritical(result))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("broker down");

        // and the stamp is NOT written, so the escalation sweep still sees it
        assertThat(result.getCriticalNotifiedAt()).isNull();
        verify(labResultRepository, never()).save(result);
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
        // The alert row and the stamp are local writes that commit with the
        // result; only the gateway hop waits for the commit, and it is handed
        // the number read while the transaction was open.
        when(smsService.deliversRealSms()).thenReturn(true);

        service.notifyIfCritical(result);

        verify(smsService).send(eq("+22670707070"), contains("Potassium"));
    }

    @Test
    void sendCriticalSmsNeedsNothingButWhatItWasHanded() {
        // The callback runs after the commit and must touch no association:
        // the number is three lazy hops from the result, and with
        // open-in-view off it would be unreachable there. It is read in the
        // transaction and passed in, so this needs no repository at all.
        service.sendCriticalSms(result.getId(), null, "unused");
        service.sendCriticalSms(result.getId(), "   ", "unused");

        verify(smsService, never()).send(anyString(), anyString());
        verify(labResultRepository, never()).findById(any());
    }

    @Test
    void sendCriticalSmsSwallowsAGatewayFailure() {
        // Already committed and already alerted in-app: a gateway that throws
        // must not propagate out of the callback.
        when(smsService.deliversRealSms()).thenReturn(true);
        doThrow(new IllegalStateException("gateway down"))
            .when(smsService).send(anyString(), anyString());

        service.sendCriticalSms(result.getId(), "+22670707070", "Critical potassium");

        verify(smsService).send(anyString(), anyString());
    }

    @Test
    void stampsWithoutNotifyingWhenNoOrderingUser() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.getLabOrder().setOrderingStaff(null); // SYSTEM-actor path

        service.notifyIfCritical(result);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(result.getCriticalNotifiedAt()).isNotNull(); // sweep convergence
        verify(labResultRepository).save(result);
    }

    @Test
    void escalateOverdueStopsChasingAResultThatIsNoLongerCritical() {
        // QA D305: glucose 5.0 (range 3.9-6.1, NORMAL) still carried an old
        // critical stamp and was paged as critical every 30 min, up to level 84.
        result.setResultValue("5.0");
        result.setAbnormalFlag(AbnormalFlag.NORMAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusDays(1));
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(31));
        result.setCriticalEscalationLevel((short) 84);
        when(labResultMapper.severityFlagOf(result)).thenReturn("NORMAL");
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
            .thenReturn(List.of(result));

        Integer escalated = service.escalateOverdue();

        assertThat(escalated).isZero();
        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(result.getCriticalNotifiedAt()).isNull();
        assertThat(result.getCriticalEscalatedAt()).isNull();
        assertThat(result.getCriticalEscalationLevel()).isZero();
        verify(labResultRepository).save(result);
    }

    @Test
    void escalateOverdueAsksOnlyForRowsBelowTheRoundCap() {
        // QA D305: the chain is capped; the sweep query excludes capped rows.
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
            .thenReturn(List.of());

        service.escalateOverdue();

        verify(labResultRepository).findCriticalAwaitingEscalation(any(LocalDateTime.class), eq((short) 12));
    }

    @Test
    void escalateOverdueStopsAtTheRoundCap() {
        // QA D305: the round that reaches the cap still pages, and is the last.
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        result.setCriticalEscalationLevel((short) 11);
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
            .thenReturn(List.of(result));

        Integer escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 12);
    }

    @Test
    void escalateOverdueReturnsTheBoxedCountTheLockedProxyReliesOn() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
            .thenReturn(List.of(result));

        Integer escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        verify(labResultRepository).save(result);
    }

    @Test
    void escalateOverdueNotifiesAndStampsOnce() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
            .thenReturn(List.of(result));

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        verify(notificationService).created(
            contains("ESCALADE"), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT_ESCALATION"));
        assertThat(result.getCriticalEscalatedAt()).isNotNull();
        verify(labResultRepository).save(result);
    }

    @Test
    void escalateOverdueStampsEvenWithoutResolvableUser() {
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        result.getLabOrder().setOrderingStaff(null);
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
            .thenReturn(List.of(result));

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        assertThat(result.getCriticalEscalatedAt()).isNotNull();
        verify(notificationService, never()).created(anyString(), anyString(), anyString());
    }

    @Test
    void escalateOverdueSkipsFailingRowAndContinues() {
        LabResult broken = new LabResult();
        broken.setId(UUID.randomUUID());
        broken.setLabOrder(result.getLabOrder());
        broken.setResultValue("x");
        broken.setAbnormalFlag(AbnormalFlag.CRITICAL);
        broken.setCriticalNotifiedAt(LocalDateTime.now().minusHours(2));
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));

        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
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
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
            .thenReturn(List.of(result));

        service.escalateOverdue();

        // The provider stays on the list — they can still act clinically — but
        // they are no longer the ONLY person told.
        verify(notificationService).created(
            anyString(), eq("dr.diallo"), eq("CRITICAL_LAB_RESULT_ESCALATION"));
        verify(notificationService).created(
            anyString(), eq("admin.kabore"), eq("CRITICAL_LAB_RESULT_ESCALATION"));
        verify(notificationService).created(
            anyString(), eq("admin.sawadogo"), eq("CRITICAL_LAB_RESULT_ESCALATION"));
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 2);
    }

    @Test
    void firstRoundIsStillJustANudgeToTheProvider() {
        givenHospital();
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
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
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
            .thenReturn(List.of(result));

        service.escalateOverdue();

        // There is deliberately no round cap. Going quiet on an unacknowledged
        // critical value is the exact failure this service exists to prevent, so
        // a cap would reintroduce it with extra steps.
        verify(notificationService).created(
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

    @Test
    void readBackTreatsTheFrenchDecimalCommaAsTheSameNumber() {
        // QA D415: "28,5" is how a French clinician repeats 28.5.
        assertThat(CriticalValueNotificationService.normalizeForComparison("28,5"))
            .isEqualTo(CriticalValueNotificationService.normalizeForComparison("28.50"));
        assertThat(CriticalValueNotificationService.normalizeForComparison("2,85"))
            .isNotEqualTo(CriticalValueNotificationService.normalizeForComparison("28.5"));
    }

    @Test
    void escalateOverdueKeepsEscalatingAStillCriticalRangeGradedResult() {
        // QA D402: GLU 30.2 is CRITICAL by its range, not by an analyzer flag;
        // the sweep must grade it from the stored definition and climb.
        result.setAbnormalFlag(null);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(35));
        when(labResultMapper.severityFlagOf(result)).thenReturn("CRITICAL");
        when(labResultMapper.raisesCriticalAlert("CRITICAL")).thenReturn(true);
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
            .thenReturn(List.of(result));

        assertThat(service.escalateOverdue()).isEqualTo(1);
        assertThat(result.getCriticalNotifiedAt()).isNotNull();
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 1);
    }

    @Test
    void escalateOverdueKeepsAnUngradableResultInTheChain() {
        // QA D402: a grade the sweep cannot establish is not proof the value is
        // no longer critical; it paged once, so it keeps escalating.
        result.setAbnormalFlag(null);
        result.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(35));
        when(labResultMapper.severityFlagOf(result)).thenReturn("UNSPECIFIED");
        when(labResultRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyShort()))
            .thenReturn(List.of(result));

        assertThat(service.escalateOverdue()).isEqualTo(1);
        assertThat(result.getCriticalNotifiedAt()).isNotNull();
    }
}
