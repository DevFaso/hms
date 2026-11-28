package com.example.hms.service;

import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import net.javacrumbs.shedlock.core.LockAssert;
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
import org.springframework.context.MessageSource;
import com.example.hms.i18n.TestMessageSources;

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
    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS)
    private com.example.hms.service.RenderingNotificationService notificationService;
    @Mock
    private SmsService smsService;
    @Mock
    private ImagingReportRepository imagingReportRepository;
    @Mock
    private StaffRepository staffRepository;
    /** Scorecard K4: the one ladder; its steps are computed for real, from the default minutes (15, 30, 60). */
    @Mock
    private com.example.hms.service.escalation.UrgentEscalationSupport ladder;
    @Mock
    private UserRepository userRepository;

    @Spy private MessageSource messageSource = TestMessageSources.bundles();

    @InjectMocks
    private ImagingCriticalNotificationService service;

    private UUID hospitalId;
    private UUID orderingUserId;
    private Hospital hospital;
    private ImagingOrder order;

    @BeforeEach
    void setUp() {
        // The body asserts it runs under the lock; unit tests have no proxy.
        LockAssert.TestHelper.makeAllAssertsPass(true);
        org.mockito.Mockito.lenient().when(ladder.due(org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any()))
            .thenAnswer(call -> com.example.hms.service.lab.CriticalResultEscalationLadder.due(call.getArgument(0),
                call.getArgument(1), call.getArgument(2),
                com.example.hms.service.lab.CriticalResultEscalationLadder.Minutes.DEFAULT, call.getArgument(4)));
        org.mockito.Mockito.lenient().when(ladder.seniors(any(), any(), any())).thenReturn(new java.util.LinkedHashSet<>());
        org.mockito.Mockito.lenient().when(ladder.addedAt(any(), any(), any())).thenReturn(java.util.Set.of());
        org.mockito.Mockito.lenient().when(ladder.openIncidents(any())).thenReturn(List.of());

        hospitalId = UUID.randomUUID();
        orderingUserId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Aminata");
        patient.setLastName("Diallo");

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
        verify(notificationService).created(
            message.capture(), eq("dr.ordering"), eq("CRITICAL_IMAGING_FINDING"));
        assertThat(message.getValue()).contains("Résultat d'imagerie critique");
        assertThat(report.getCriticalNotifiedAt()).isNotNull();
    }

    @Test
    void theAlertNamesTheReportTitleWhenTheReportHasOne() {
        ImagingReport report = flagged();
        report.setReportTitle("CT Head without contrast");

        service.notifyIfCritical(report);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).created(message.capture(), anyString(), anyString());
        assertThat(message.getValue()).contains("CT Head without contrast");
    }

    @Test
    void theAlertStillNamesAStudyWhenNeitherTitleNorStudyTypeIsKnown() {
        // An externally ingested study can arrive with neither. The alert still
        // has to say something a clinician can act on rather than read "null".
        ImagingReport report = flagged();
        order.setStudyType(null);

        service.notifyIfCritical(report);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).created(message.capture(), anyString(), anyString());
        assertThat(message.getValue()).contains("Examen d'imagerie").doesNotContain("null");
    }

    @Test
    void theAlertNeverRendersTheWordNullWhenThePatientHasNoRecordedName() {
        // getFullName() returns null for a patient with no names, which was
        // being concatenated straight into the alert: "…finding: CT Head for
        // null." That text reaches a clinician and goes out over SMS.
        ImagingReport report = flagged();
        order.getPatient().setFirstName(null);
        order.getPatient().setLastName(null);

        service.notifyIfCritical(report);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).created(message.capture(), anyString(), anyString());
        assertThat(message.getValue()).contains("pour patient.").doesNotContain("null");
    }

    @Test
    void doesNothingForAReportWithNoCriticalFlag() {
        service.notifyIfCritical(report());

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
    }

    @Test
    void raisesExactlyOneFirstAlertAcrossRepeatedSaves() {
        ImagingReport report = flagged();

        service.notifyIfCritical(report);
        service.notifyIfCritical(report);
        service.notifyIfCritical(report);

        verify(notificationService, times(1))
            .created(anyString(), anyString(), eq("CRITICAL_IMAGING_FINDING"));
    }

    @Test
    void stampsNotifiedEvenWithNoResolvableProviderSoTheSweepDoesNotSpin() {
        ImagingReport report = flagged();
        order.setOrderingProviderUserId(null);

        service.notifyIfCritical(report);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(report.getCriticalNotifiedAt()).isNotNull();
    }

    @Test
    void aNotificationFailureNeverPropagatesIntoTheClinicalWrite() {
        ImagingReport report = flagged();
        when(notificationService.created(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("notification bus down"));

        assertThatCode(() -> service.notifyIfCritical(report)).doesNotThrowAnyException();

        // Pin that the throwing path was actually exercised. Without this the
        // test would pass just as happily against a method that never notified.
        verify(notificationService).created(anyString(), anyString(), eq("CRITICAL_IMAGING_FINDING"));
    }

    @Test
    void aFailedFirstAlertIsStillStampedSoTheFindingCannotGoSilent() {
        // The sweep selects on criticalNotifiedAt IS NOT NULL. A report left
        // unstamped because the bus was down would drop out of the escalation
        // path entirely and nobody would ever be told — the exact failure this
        // service exists to prevent, arriving through the back door.
        ImagingReport report = flagged();
        when(notificationService.created(anyString(), anyString(), anyString()))
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

        // K4: the patient's initials and nothing clinical: never the study, the impression or the full name.
        verify(smsService).send("+22370000000",
            "e-Keneya : un résultat d'imagerie critique du patient AD attend votre accusé de réception. Ouvrez e-Keneya.");
    }

    @Test
    void truncatesTheImpressionSoTheAlertFitsAnSmsAndCarriesNoFullFindings() {
        ImagingReport report = flagged();
        report.setImpression("x".repeat(400));

        service.notifyIfCritical(report);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).created(message.capture(), anyString(), anyString());
        assertThat(message.getValue()).hasSizeLessThan(320).contains("…");
    }

    // ── Escalation ───────────────────────────────────────────────────────

    @Test
    void theFirstStepAddsTheCoveringClinicianToTheOrderingProvider() {
        ImagingReport report = flagged();
        report.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(20));
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));
        when(ladder.seniors(hospitalId, com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER,
            "dr.ordering")).thenReturn(new java.util.LinkedHashSet<>(List.of("dr.cover")));

        assertThat(service.escalateOverdue()).isEqualTo(1);

        verify(notificationService).created(
            anyString(), eq("dr.ordering"), eq("CRITICAL_IMAGING_FINDING_ESCALATION"));
        verify(notificationService).created(
            anyString(), eq("dr.cover"), eq("CRITICAL_IMAGING_FINDING_ESCALATION"));
        assertThat(report.getCriticalEscalationLevel()).isEqualTo((short) 1);
        assertThat(report.getCriticalEscalatedAt()).isNotNull();
    }

    @Test
    void aFindingWithNoStepDueIsLeftAlone() {
        ImagingReport report = flagged();
        // Notified ten minutes ago: the covering clinician's step comes at fifteen.
        report.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(10));
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));

        assertThat(service.escalateOverdue()).isZero();

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        verify(imagingReportRepository, never()).save(any());
    }

    @Test
    void theSecondStepAddsTheMedecinChefAndNeverAnAdministrator() {
        ImagingReport report = flagged();
        report.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(35));
        report.setCriticalEscalationLevel((short) 1);
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));
        when(ladder.seniors(hospitalId, com.example.hms.service.lab.CriticalResultEscalationLadder.Step.MEDICAL_LEAD,
            "dr.ordering")).thenReturn(new java.util.LinkedHashSet<>(List.of("dr.cover", "dr.chef")));

        service.escalateOverdue();

        // The ordering provider STAYS on the list — they remain the one who knows
        // the patient; widening is not a hand-off.
        for (String clinician : List.of("dr.ordering", "dr.cover", "dr.chef")) {
            verify(notificationService).created(
                anyString(), eq(clinician), eq("CRITICAL_IMAGING_FINDING_ESCALATION"));
        }
        // Scorecard K4: the study, the patient's name and the impression never reach an administrator. The
        // service does not even read who the administrators are.
        verify(notificationService, times(3)).created(anyString(), anyString(), anyString());
        verify(staffRepository, never()).findActiveUsernamesByHospitalAndRole(any(), anyString());
        verify(ladder, never()).openIncident(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyInt());
        assertThat(report.getCriticalEscalationLevel()).isEqualTo((short) 2);
    }

    @Test
    void theLastStepSaysUnresolvedAndOpensTheIncident() {
        ImagingReport report = flagged();
        report.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(61));
        report.setCriticalEscalationLevel((short) 2);
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));
        when(ladder.seniors(any(), any(), any())).thenReturn(new java.util.LinkedHashSet<>(List.of("dr.chef")));

        service.escalateOverdue();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).created(message.capture(), eq("dr.chef"), eq("CRITICAL_IMAGING_FINDING_ESCALATION"));
        assertThat(message.getValue()).contains("NON RÉSOLU").contains("61");
        verify(ladder).openIncident(eq(com.example.hms.model.UrgentAlertIncident.SOURCE_IMAGING_REPORT),
            eq(report.getId()), eq(hospitalId), any(), eq(61L), eq(2));
        assertThat(report.getCriticalEscalationLevel()).isEqualTo((short) 3);
    }

    @Test
    void escalationKeepsFiringWithNoRoundCap() {
        ImagingReport report = flagged();
        report.setCriticalNotifiedAt(LocalDateTime.now().minusDays(30));
        report.setCriticalEscalatedAt(LocalDateTime.now().minusDays(1).minusMinutes(1));
        report.setCriticalEscalationLevel((short) 40);
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));
        when(ladder.seniors(any(), any(), any())).thenReturn(new java.util.LinkedHashSet<>(List.of("dr.chef")));
        when(smsService.deliversRealSms()).thenReturn(true);

        service.escalateOverdue();

        // Going silent on an unacknowledged critical finding is the failure this
        // whole loop exists to prevent, so step 41 is taken like step 1; it is a daily reminder by now.
        assertThat(report.getCriticalEscalationLevel()).isEqualTo((short) 41);
        verify(notificationService, times(2))
            .created(anyString(), anyString(), eq("CRITICAL_IMAGING_FINDING_ESCALATION"));
        // A reminder texts nobody.
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    void anAcknowledgedFindingClosesItsIncident() {
        ImagingReport report = flagged();
        LocalDateTime acknowledgedAt = LocalDateTime.now().minusMinutes(2);
        report.setCriticalResultAcknowledgedAt(acknowledgedAt);
        when(ladder.openIncidents(com.example.hms.model.UrgentAlertIncident.SOURCE_IMAGING_REPORT)).thenReturn(List.of(
            com.example.hms.model.UrgentAlertIncident.builder().sourceId(report.getId()).build()));
        when(imagingReportRepository.findById(report.getId())).thenReturn(Optional.of(report));
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of());

        service.escalateOverdue();

        verify(ladder).closeIncident(com.example.hms.model.UrgentAlertIncident.SOURCE_IMAGING_REPORT, report.getId(),
            com.example.hms.model.UrgentAlertIncident.RESOLVED_ACKNOWLEDGED, null, acknowledgedAt);
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
    void escalateOverdueReturnsTheBoxedCountTheLockedProxyReliesOn() {
        ImagingReport report = flagged();
        report.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));

        Integer escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        assertThat(report.getCriticalEscalatedAt()).isNotNull();
    }

    @Test
    void escalationStampsEvenWithNobodyToNotifySoTheIntervalAdvances() {
        ImagingReport report = flagged();
        report.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        order.setOrderingProviderUserId(null);
        when(imagingReportRepository.findCriticalAwaitingEscalation(any())).thenReturn(List.of(report));

        service.escalateOverdue();

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(report.getCriticalEscalatedAt()).isNotNull();
        assertThat(report.getCriticalEscalationLevel()).isEqualTo((short) 1);
    }
}
