package com.example.hms.service;

import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The critical-imaging chain (Tier 2 item 27).
 *
 * <p>Several tests here exist because the lab equivalent shipped WITHOUT the
 * behaviour and the 2026-08-21 reassessment found it: escalation that repeats
 * rather than firing once, and that widens past the person who already ignored
 * the first alert. Those are pinned, not assumed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImagingCriticalNotificationServiceTest {

    @Spy
    private Clock clock = Clock.systemDefaultZone();
    @Mock
    private NotificationService notificationService;
    @Mock
    private SmsService smsService;
    @Mock
    private ImagingReportRepository imagingReportRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ImagingCriticalNotificationService service;

    private UUID hospitalId;
    private UUID orderingUserId;
    private Hospital hospital;
    private ImagingOrder order;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "escalateAfterMinutes", 30L);

        hospitalId = UUID.randomUUID();
        orderingUserId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(hospital);
        order.setPatient(patient);
        order.setStudyType("CT Head");
        order.setOrderingProviderUserId(orderingUserId);

        User orderingUser = new User();
        orderingUser.setId(orderingUserId);
        orderingUser.setUsername("dr.ordering");
        orderingUser.setPhoneNumber("+22370000000");
        when(userRepository.findById(orderingUserId)).thenReturn(Optional.of(orderingUser));

        when(smsService.deliversRealSms()).thenReturn(false);
        when(imagingReportRepository.save(any(ImagingReport.class))).thenAnswer(i -> i.getArgument(0));
    }

    private ImagingReport report() {
        ImagingReport report = new ImagingReport();
        report.setId(UUID.randomUUID());
        report.setHospital(hospital);
        report.setImagingOrder(order);
        report.setImpression("Large left frontal intracranial haemorrhage with midline shift.");
        return report;
    }

    private ImagingReport flagged() {
        ImagingReport report = report();
        report.setCriticalResultFlaggedAt(LocalDateTime.now());
        return report;
    }

    // ── First alert ──────────────────────────────────────────────────────

    @Test
    void notifiesTheOrderingProviderWhenAFindingIsFlagged() {
        ImagingReport report = flagged();

        service.notifyIfCritical(report);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createNotification(
            message.capture(), eq("dr.ordering"), eq("CRITICAL_IMAGING_FINDING"));
        assertThat(message.getValue()).contains("Critical imaging finding");
        assertThat(report.getCriticalNotifiedAt()).isNotNull();
    }

    @Test
    void doesNothingForAReportWithNoCriticalFlag() {
        service.notifyIfCritical(report());

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void raisesExactlyOneFirstAlertAcrossRepeatedSaves() {
        ImagingReport report = flagged();

        service.notifyIfCritical(report);
        service.notifyIfCritical(report);
        service.notifyIfCritical(report);

        verify(notificationService, times(1))
            .createNotification(anyString(), anyString(), eq("CRITICAL_IMAGING_FINDING"));
    }

    @Test
    void stampsNotifiedEvenWithNoResolvableProviderSoTheSweepDoesNotSpin() {
        ImagingReport report = flagged();
        order.setOrderingProviderUserId(null);

        service.notifyIfCritical(report);

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
        assertThat(report.getCriticalNotifiedAt()).isNotNull();
    }

    @Test
    void aNotificationFailureNeverPropagatesIntoTheClinicalWrite() {
        ImagingReport report = flagged();
        when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("notification bus down"));

        assertThatCode(() -> service.notifyIfCritical(report)).doesNotThrowAnyException();

        // Pin that the throwing path was actually exercised. Without this the
        // test would pass just as happily against a method that never notified.
        verify(notificationService).createNotification(anyString(), anyString(), eq("CRITICAL_IMAGING_FINDING"));
    }

    @Test
    void aFailedFirstAlertIsStillStampedSoTheFindingCannotGoSilent() {
        // The sweep selects on criticalNotifiedAt IS NOT NULL. A report left
        // unstamped because the bus was down would drop out of the escalation
        // path entirely and nobody would ever be told — the exact failure this
        // service exists to prevent, arriving through the back door.
        ImagingReport report = flagged();
        when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("notification bus down"));

        service.notifyIfCritical(report);

        assertThat(report.getCriticalNotifiedAt()).isNotNull();
        verify(imagingReportRepository).save(report);
    }

    @Test
    void neverRoutesClinicalAlertsThroughTheMockSmsTransport() {
        service.notifyIfCritical(flagged());

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    void sendsSmsWhenTheRealTransportIsLive() {
        when(smsService.deliversRealSms()).thenReturn(true);

        service.notifyIfCritical(flagged());

        verify(smsService).send(eq("+22370000000"), anyString());
    }

    @Test
    void truncatesTheImpressionSoTheAlertFitsAnSmsAndCarriesNoFullFindings() {
        ImagingReport report = flagged();
        report.setImpression("x".repeat(400));

        service.notifyIfCritical(report);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createNotification(message.capture(), anyString(), anyString());
        assertThat(message.getValue()).hasSizeLessThan(320).contains("…");
    }

    // ── Escalation ───────────────────────────────────────────────────────

    @Test
    void escalationRoundOneNudgesOnlyTheOrderingProvider() {
        ImagingReport report = flagged();
        report.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));

        assertThat(service.escalateOverdue()).isEqualTo(1);

        verify(notificationService).createNotification(
            anyString(), eq("dr.ordering"), eq("CRITICAL_IMAGING_FINDING_ESCALATION"));
        verify(staffRepository, never()).findActiveUsernamesByHospitalAndRole(any(), anyString());
        assertThat(report.getCriticalEscalationLevel()).isEqualTo((short) 1);
        assertThat(report.getCriticalEscalatedAt()).isNotNull();
    }

    @Test
    void escalationWidensToHospitalAdminsFromRoundTwo() {
        ImagingReport report = flagged();
        report.setCriticalNotifiedAt(LocalDateTime.now().minusHours(2));
        report.setCriticalEscalationLevel((short) 1);
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_HOSPITAL_ADMIN"))
            .thenReturn(List.of("admin.one", "admin.two"));

        service.escalateOverdue();

        // The ordering provider STAYS on the list — they remain the one who can
        // act clinically; widening is not a hand-off.
        verify(notificationService).createNotification(
            anyString(), eq("dr.ordering"), eq("CRITICAL_IMAGING_FINDING_ESCALATION"));
        verify(notificationService).createNotification(
            anyString(), eq("admin.one"), eq("CRITICAL_IMAGING_FINDING_ESCALATION"));
        verify(notificationService).createNotification(
            anyString(), eq("admin.two"), eq("CRITICAL_IMAGING_FINDING_ESCALATION"));
        assertThat(report.getCriticalEscalationLevel()).isEqualTo((short) 2);
    }

    @Test
    void escalationKeepsFiringWithNoRoundCap() {
        ImagingReport report = flagged();
        report.setCriticalNotifiedAt(LocalDateTime.now().minusDays(1));
        report.setCriticalEscalationLevel((short) 9);
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(any(), anyString()))
            .thenReturn(List.of("admin.one"));

        service.escalateOverdue();

        // Going silent on an unacknowledged critical finding is the failure this
        // whole loop exists to prevent, so round 10 fires like round 1.
        assertThat(report.getCriticalEscalationLevel()).isEqualTo((short) 10);
        verify(notificationService, times(2))
            .createNotification(anyString(), anyString(), eq("CRITICAL_IMAGING_FINDING_ESCALATION"));
    }

    @Test
    void oneBadRowNeverStallsTheSweep() {
        ImagingReport good = flagged();
        good.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        ImagingReport bad = flagged();
        bad.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        bad.setHospital(null);
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(bad, good));
        when(imagingReportRepository.save(bad)).thenThrow(new IllegalStateException("row is wedged"));

        assertThat(service.escalateOverdue()).isEqualTo(1);
        assertThat(good.getCriticalEscalationLevel()).isEqualTo((short) 1);
    }

    @Test
    void escalationStampsEvenWithNobodyToNotifySoTheIntervalAdvances() {
        ImagingReport report = flagged();
        report.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        order.setOrderingProviderUserId(null);
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));

        service.escalateOverdue();

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
        assertThat(report.getCriticalEscalatedAt()).isNotNull();
        assertThat(report.getCriticalEscalationLevel()).isEqualTo((short) 1);
    }
}
