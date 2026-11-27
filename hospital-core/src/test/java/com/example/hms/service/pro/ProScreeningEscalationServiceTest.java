package com.example.hms.service.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.PanelAssignmentStatus;
import com.example.hms.enums.PanelRole;
import com.example.hms.model.Hospital;
import com.example.hms.model.Notification;
import com.example.hms.model.PanelAssignment;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.pro.ProInstrument;
import com.example.hms.model.pro.ProResponse;
import com.example.hms.repository.PanelAssignmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pro.ProResponseRepository;
import com.example.hms.service.SmsService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
class ProScreeningEscalationServiceTest {

    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS) private com.example.hms.service.RenderingNotificationService notificationService;
    @Mock private SmsService smsService;
    @Mock private ProResponseRepository responseRepository;
    @Mock private PanelAssignmentRepository panelAssignmentRepository;
    @Mock private StaffRepository staffRepository;
    /** Scorecard K4: the one ladder. Each sweep test says which step it stands at. */
    @Mock private com.example.hms.service.escalation.UrgentEscalationSupport ladder;
    @Mock private UserRepository userRepository;

    @Spy private MessageSource messageSource = TestMessageSources.bundles();

    /** Fixed instant: the stamps and the sweep cutoff come from the injected clock. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 2, 0);
    @Spy private Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @InjectMocks private ProScreeningEscalationService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID recorderId = UUID.randomUUID();
    private ProResponse response;
    private User recorder;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "fallbackRole", "ROLE_MIDWIFE");

        recorder = new User();
        recorder.setId(recorderId);
        recorder.setUsername("midwife.kone");
        recorder.setPhoneNumber("+22670000000");

        Hospital hospital = Hospital.builder().build();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setLastName("Traore");

        response = ProResponse.builder()
            .instrument(ProInstrument.builder().code("EPDS").build())
            .patient(patient)
            .hospital(hospital)
            .recordedByUserId(recorderId)
            .administeredAt(NOW)
            .answers("{}")
            .build();
        response.setId(UUID.randomUUID());

        lenient().when(userRepository.findById(recorderId)).thenReturn(Optional.of(recorder));
        lenient().when(panelAssignmentRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(notificationService.created(anyString(), anyString(), anyString()))
            .thenReturn(new Notification());
        lenient().when(smsService.deliversRealSms()).thenReturn(false);
        lenient().when(ladder.seniors(any(), any(), any())).thenReturn(new java.util.LinkedHashSet<>());
        lenient().when(ladder.addedAt(any(), any(), any())).thenReturn(Set.of());
        lenient().when(ladder.openIncidents(any())).thenReturn(List.of());
    }

    /** The ladder says this step is due for whatever the sweep asks about. */
    private void stepDue(com.example.hms.service.lab.CriticalResultEscalationLadder.Step step) {
        when(ladder.due(org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any()))
            .thenReturn(Optional.of(step));
    }

    private static PanelAssignment assignmentFor(String username) {
        User user = new User();
        user.setUsername(username);
        Staff staff = Staff.builder().build();
        staff.setUser(user);
        return PanelAssignment.builder().providerStaff(staff).build();
    }

    // ── notifyOnRecord ────────────────────────────────────────────────

    @Test
    void criticalResponseNotifiesRecorderAndPanelOwnersWithoutAnswersOrScore() {
        response.setCriticalItemPositive(true);
        response.setScreenPositive(true);
        response.setTotalScore(17);
        when(panelAssignmentRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                patientId, hospitalId, PanelRole.PRIMARY_PROVIDER, PanelAssignmentStatus.ACTIVE))
            .thenReturn(Optional.of(assignmentFor("dr.ouedraogo")));
        when(panelAssignmentRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                patientId, hospitalId, PanelRole.CHW, PanelAssignmentStatus.ACTIVE))
            .thenReturn(Optional.of(assignmentFor("chw.sawadogo")));

        service.notifyOnRecord(response);

        verify(notificationService).created(contains("Réponse positive à l'item de sécurité EPDS"), eq("midwife.kone"),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        verify(notificationService).created(anyString(), eq("dr.ouedraogo"),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        verify(notificationService).created(anyString(), eq("chw.sawadogo"),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        verify(notificationService, times(3)).created(anyString(), anyString(), anyString());
        verify(notificationService, never()).created(contains("17"), anyString(), anyString());
        // No fallback when the patient has owners; no admins on round 1.
        verify(staffRepository, never()).findActiveUsernamesByHospitalAndRole(any(), anyString());
        assertThat(response.getNotifiedAt()).isNotNull();
        verify(responseRepository).save(response);
    }

    @Test
    void screenPositiveWithoutCriticalIsAOneShotReferralPrompt() {
        response.setScreenPositive(true);

        service.notifyOnRecord(response);

        verify(notificationService).created(contains("Dépistage EPDS positif"), eq("midwife.kone"),
            eq(ProScreeningEscalationService.SCREEN_POSITIVE_TYPE));
        verify(notificationService, never()).created(anyString(), anyString(),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        assertThat(response.getNotifiedAt()).isNotNull();
    }

    @Test
    void negativeScreenNotifiesNobodyAndLeavesTheRowAlone() {
        service.notifyOnRecord(response);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        verify(responseRepository, never()).save(any());
        assertThat(response.getNotifiedAt()).isNull();
    }

    @Test
    void selfReportWithNoRecorderAndNoPanelFallsThroughToTheFallbackRole() {
        response.setCriticalItemPositive(true);
        response.setRecordedByUserId(null);
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_MIDWIFE"))
            .thenReturn(List.of("midwife.a", "midwife.b"));

        service.notifyOnRecord(response);

        verify(notificationService).created(anyString(), eq("midwife.a"),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        verify(notificationService).created(anyString(), eq("midwife.b"),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        verify(userRepository, never()).findById(any());
        assertThat(response.getNotifiedAt()).isNotNull();
    }

    @Test
    void nobodyToTellMeansNobodyWasTold() {
        // Self-report, no panel, no midwife on the roster: the patient must not read "care team alerted".
        response.setCriticalItemPositive(true);
        response.setRecordedByUserId(null);
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_MIDWIFE"))
            .thenReturn(List.of());

        service.notifyOnRecord(response);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(response.getNotifiedAt()).isNull();
        verify(responseRepository, never()).save(any());
    }

    @Test
    void notifyOnRecordNeverPropagates() {
        response.setCriticalItemPositive(true);
        doThrow(new IllegalStateException("notification store down"))
            .when(notificationService).created(anyString(), anyString(), anyString());

        assertThatCode(() -> service.notifyOnRecord(response)).doesNotThrowAnyException();
        // The stamp is skipped, so the sweep picks the row up (COALESCE onto createdAt).
        assertThat(response.getNotifiedAt()).isNull();
    }

    @Test
    void smsGoesToTheRecorderOnlyOnARealTransport() {
        response.setCriticalItemPositive(true);
        when(smsService.deliversRealSms()).thenReturn(true);

        service.notifyOnRecord(response);

        // K4: never the instrument (EPDS discloses a perinatal depression screen) nor the full name.
        verify(smsService).send(eq("+22670000000"), contains("une réponse à un questionnaire du patient AT"));
        verify(smsService, never()).send(anyString(), contains("EPDS"));
    }

    @Test
    void smsIsSkippedOnTheMockTransport() {
        response.setCriticalItemPositive(true);

        service.notifyOnRecord(response);

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    void smsFailureDoesNotBlockTheNotifications() {
        response.setCriticalItemPositive(true);
        when(smsService.deliversRealSms()).thenReturn(true);
        doThrow(new RuntimeException("gateway timeout")).when(smsService).send(anyString(), anyString());

        service.notifyOnRecord(response);

        verify(notificationService).created(anyString(), eq("midwife.kone"), anyString());
        assertThat(response.getNotifiedAt()).isNotNull();
    }

    // ── escalateOverdue ───────────────────────────────────────────────

    @Test
    void theFirstStepAddsTheCoveringClinicianAndStampsTheRow() {
        response.setCriticalItemPositive(true);
        stepDue(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER);
        when(ladder.seniors(hospitalId, com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER,
            "midwife.kone")).thenReturn(new java.util.LinkedHashSet<>(List.of("dr.cover")));
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        verify(notificationService).created(contains("ESCALADE"), eq("midwife.kone"),
            eq(ProScreeningEscalationService.ESCALATION_TYPE));
        verify(notificationService).created(contains("ESCALADE"), eq("dr.cover"),
            eq(ProScreeningEscalationService.ESCALATION_TYPE));
        assertThat(response.getEscalationLevel()).isEqualTo((short) 1);
        assertThat(response.getLastEscalationAt()).isNotNull();
        verify(responseRepository).save(response);
        verify(ladder, never()).openIncident(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void sweepCutoffAndRoundStampComeFromTheInjectedClock() {
        response.setCriticalItemPositive(true);
        stepDue(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER);
        // No step of the ladder is shorter than five minutes: the sweep reads the rows older than that.
        when(responseRepository.findCriticalAwaitingEscalation(NOW.minusMinutes(5)))
            .thenReturn(List.of(response));

        assertThat(service.escalateOverdue()).isEqualTo(1);

        assertThat(response.getLastEscalationAt()).isEqualTo(NOW);
        assertThat(response.getNotifiedAt()).isEqualTo(NOW);
    }

    @Test
    void aResponseWithNoStepDueIsLeftAlone() {
        response.setCriticalItemPositive(true);
        when(ladder.due(org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));

        assertThat(service.escalateOverdue()).isZero();

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        verify(responseRepository, never()).save(any());
    }

    @Test
    void theSecondStepAddsTheMedecinChefKeepsEarlierRecipientsAndNeverAnAdministrator() {
        response.setCriticalItemPositive(true);
        response.setEscalationLevel((short) 1);
        stepDue(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.MEDICAL_LEAD);
        when(ladder.seniors(hospitalId, com.example.hms.service.lab.CriticalResultEscalationLadder.Step.MEDICAL_LEAD,
            "midwife.kone")).thenReturn(new java.util.LinkedHashSet<>(List.of("dr.cover", "dr.chef")));
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));

        service.escalateOverdue();

        for (String clinician : List.of("midwife.kone", "dr.cover", "dr.chef")) {
            verify(notificationService).created(anyString(), eq(clinician),
                eq(ProScreeningEscalationService.ESCALATION_TYPE));
        }
        // Scorecard K4: the instrument and the patient's name never reach the hospital's administrators.
        verify(staffRepository, never()).findActiveUsernamesByHospitalAndRole(any(), eq("ROLE_HOSPITAL_ADMIN"));
        verify(notificationService, times(3)).created(anyString(), anyString(), anyString());
        assertThat(response.getEscalationLevel()).isEqualTo((short) 2);
    }

    @Test
    void theLastStepSaysUnresolvedAndOpensTheIncidentAndRemindersGoOnWithNoCap() {
        response.setCriticalItemPositive(true);
        response.setEscalationLevel((short) 40);
        stepDue(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.REMINDER);
        when(ladder.seniors(any(), any(), any())).thenReturn(new java.util.LinkedHashSet<>(List.of("dr.chef")));
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));

        assertThat(service.escalateOverdue()).isEqualTo(1);

        verify(notificationService).created(contains("NON RÉSOLU"), eq("dr.chef"),
            eq(ProScreeningEscalationService.ESCALATION_TYPE));
        verify(ladder).openIncident(eq(com.example.hms.model.UrgentAlertIncident.SOURCE_PRO_RESPONSE),
            eq(response.getId()), eq(hospitalId), eq(NOW), org.mockito.ArgumentMatchers.anyLong(), eq(2));
        assertThat(response.getEscalationLevel()).isEqualTo((short) 41);
        // A reminder texts nobody.
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    void anAcknowledgedResponseClosesItsIncident() {
        UUID acknowledger = UUID.randomUUID();
        response.setCriticalItemPositive(true);
        response.setAcknowledgedAt(NOW.minusMinutes(3));
        response.setAcknowledgedByUserId(acknowledger);
        when(ladder.openIncidents(com.example.hms.model.UrgentAlertIncident.SOURCE_PRO_RESPONSE)).thenReturn(List.of(
            com.example.hms.model.UrgentAlertIncident.builder().sourceId(response.getId()).build()));
        when(responseRepository.findById(response.getId())).thenReturn(Optional.of(response));
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class))).thenReturn(List.of());

        service.escalateOverdue();

        verify(ladder).closeIncident(com.example.hms.model.UrgentAlertIncident.SOURCE_PRO_RESPONSE, response.getId(),
            com.example.hms.model.UrgentAlertIncident.RESOLVED_ACKNOWLEDGED, acknowledger, NOW.minusMinutes(3));
    }

    @Test
    void stampsTheRowEvenWhenNobodyCanBeResolved() {
        response.setCriticalItemPositive(true);
        response.setRecordedByUserId(null);
        stepDue(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER);
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_MIDWIFE"))
            .thenReturn(List.of());

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        assertThat(response.getEscalationLevel()).isEqualTo((short) 1);
        assertThat(response.getLastEscalationAt()).isNotNull();
        // The ladder advances; the promise to the patient does not.
        assertThat(response.getNotifiedAt()).isNull();
        verify(responseRepository).save(response);
    }

    @Test
    void aLaterStepThatReachesSomebodyMakesThePromiseTrue() {
        response.setCriticalItemPositive(true);
        response.setRecordedByUserId(null);
        response.setEscalationLevel((short) 1);
        stepDue(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.MEDICAL_LEAD);
        when(ladder.seniors(any(), any(), any())).thenReturn(new java.util.LinkedHashSet<>(List.of("dr.chef")));
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_MIDWIFE"))
            .thenReturn(List.of());

        service.escalateOverdue();

        verify(notificationService).created(anyString(), eq("dr.chef"),
            eq(ProScreeningEscalationService.ESCALATION_TYPE));
        assertThat(response.getNotifiedAt()).isNotNull();
    }

    @Test
    void oneBadRowDoesNotStallTheSweep() {
        ProResponse broken = ProResponse.builder().build();
        broken.setId(UUID.randomUUID()); // no hospital/patient — recipients() copes, save() is made to fail
        response.setCriticalItemPositive(true);
        stepDue(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER);
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(broken, response));
        when(responseRepository.save(broken)).thenThrow(new IllegalStateException("stale row"));

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        verify(responseRepository).save(response);
    }

    @Test
    void smsOncePerStepToTheRecorderNotOncePerRecipient() {
        response.setCriticalItemPositive(true);
        response.setEscalationLevel((short) 1);
        stepDue(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.MEDICAL_LEAD);
        when(ladder.seniors(any(), any(), any())).thenReturn(new java.util.LinkedHashSet<>(List.of("dr.a", "dr.b")));
        when(ladder.addedAt(any(), any(), any())).thenReturn(Set.of("dr.b"));
        when(smsService.deliversRealSms()).thenReturn(true);
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));

        service.escalateOverdue();

        verify(notificationService, times(3)).created(anyString(), anyString(), anyString());
        verify(smsService, times(1)).send(anyString(), anyString());
        // The clinicians the step adds get the ladder's own SMS: the patient's initials and nothing else.
        verify(ladder).smsAfterCommit(eq(Set.of("dr.b")), eq("AT"), eq(response.getId()));
    }

    // ── recipients ────────────────────────────────────────────────────

    @Test
    void recipientsDeduplicateWhenTheRecorderIsAlsoThePanelOwner() {
        when(panelAssignmentRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                patientId, hospitalId, PanelRole.PRIMARY_PROVIDER, PanelAssignmentStatus.ACTIVE))
            .thenReturn(Optional.of(assignmentFor("midwife.kone")));

        Set<String> recipients = service.recipients(response);

        assertThat(recipients).containsExactly("midwife.kone");
    }

    @Test
    void recipientsSkipAPanelOwnerWithoutAUserAccount() {
        PanelAssignment orphan = PanelAssignment.builder().providerStaff(Staff.builder().build()).build();
        when(panelAssignmentRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                patientId, hospitalId, PanelRole.CHW, PanelAssignmentStatus.ACTIVE))
            .thenReturn(Optional.of(orphan));

        Set<String> recipients = service.recipients(response);

        assertThat(recipients).containsExactly("midwife.kone");
    }
}
