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
import com.example.hms.service.NotificationService;
import com.example.hms.service.SmsService;
import java.time.LocalDateTime;
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

@ExtendWith(MockitoExtension.class)
class ProScreeningEscalationServiceTest {

    @Mock private NotificationService notificationService;
    @Mock private SmsService smsService;
    @Mock private ProResponseRepository responseRepository;
    @Mock private PanelAssignmentRepository panelAssignmentRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private ProScreeningEscalationService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID recorderId = UUID.randomUUID();
    private ProResponse response;
    private User recorder;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "escalateAfterMinutes", 30L);
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
            .administeredAt(LocalDateTime.now())
            .answers("{}")
            .build();
        response.setId(UUID.randomUUID());

        lenient().when(userRepository.findById(recorderId)).thenReturn(Optional.of(recorder));
        lenient().when(panelAssignmentRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenReturn(new Notification());
        lenient().when(smsService.deliversRealSms()).thenReturn(false);
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

        verify(notificationService).createNotification(contains("EPDS safety item"), eq("midwife.kone"),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        verify(notificationService).createNotification(anyString(), eq("dr.ouedraogo"),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        verify(notificationService).createNotification(anyString(), eq("chw.sawadogo"),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        verify(notificationService, times(3)).createNotification(anyString(), anyString(), anyString());
        verify(notificationService, never()).createNotification(contains("17"), anyString(), anyString());
        // No fallback when the patient has owners; no admins on round 1.
        verify(staffRepository, never()).findActiveUsernamesByHospitalAndRole(any(), anyString());
        assertThat(response.getNotifiedAt()).isNotNull();
        verify(responseRepository).save(response);
    }

    @Test
    void screenPositiveWithoutCriticalIsAOneShotReferralPrompt() {
        response.setScreenPositive(true);

        service.notifyOnRecord(response);

        verify(notificationService).createNotification(contains("screen positive"), eq("midwife.kone"),
            eq(ProScreeningEscalationService.SCREEN_POSITIVE_TYPE));
        verify(notificationService, never()).createNotification(anyString(), anyString(),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        assertThat(response.getNotifiedAt()).isNotNull();
    }

    @Test
    void negativeScreenNotifiesNobodyAndLeavesTheRowAlone() {
        service.notifyOnRecord(response);

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
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

        verify(notificationService).createNotification(anyString(), eq("midwife.a"),
            eq(ProScreeningEscalationService.CRITICAL_TYPE));
        verify(notificationService).createNotification(anyString(), eq("midwife.b"),
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

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
        assertThat(response.getNotifiedAt()).isNull();
        verify(responseRepository, never()).save(any());
    }

    @Test
    void notifyOnRecordNeverPropagates() {
        response.setCriticalItemPositive(true);
        doThrow(new IllegalStateException("notification store down"))
            .when(notificationService).createNotification(anyString(), anyString(), anyString());

        assertThatCode(() -> service.notifyOnRecord(response)).doesNotThrowAnyException();
        // The stamp is skipped, so the sweep picks the row up (COALESCE onto createdAt).
        assertThat(response.getNotifiedAt()).isNull();
    }

    @Test
    void smsGoesToTheRecorderOnlyOnARealTransport() {
        response.setCriticalItemPositive(true);
        when(smsService.deliversRealSms()).thenReturn(true);

        service.notifyOnRecord(response);

        verify(smsService).send(eq("+22670000000"), contains("EPDS safety item"));
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

        verify(notificationService).createNotification(anyString(), eq("midwife.kone"), anyString());
        assertThat(response.getNotifiedAt()).isNotNull();
    }

    // ── escalateOverdue ───────────────────────────────────────────────

    @Test
    void firstSweepRoundWidensNothingButStampsTheRow() {
        response.setCriticalItemPositive(true);
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        verify(notificationService).createNotification(contains("ESCALATION"), eq("midwife.kone"),
            eq(ProScreeningEscalationService.ESCALATION_TYPE));
        verify(staffRepository, never()).findActiveUsernamesByHospitalAndRole(any(), eq("ROLE_HOSPITAL_ADMIN"));
        assertThat(response.getEscalationLevel()).isEqualTo((short) 1);
        assertThat(response.getLastEscalationAt()).isNotNull();
        verify(responseRepository).save(response);
    }

    @Test
    void secondRoundAddsHospitalAdminsAndKeepsEarlierRecipients() {
        response.setCriticalItemPositive(true);
        response.setEscalationLevel((short) 1);
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_HOSPITAL_ADMIN"))
            .thenReturn(List.of("admin.zongo"));

        service.escalateOverdue();

        verify(notificationService).createNotification(anyString(), eq("midwife.kone"),
            eq(ProScreeningEscalationService.ESCALATION_TYPE));
        verify(notificationService).createNotification(anyString(), eq("admin.zongo"),
            eq(ProScreeningEscalationService.ESCALATION_TYPE));
        assertThat(response.getEscalationLevel()).isEqualTo((short) 2);
    }

    @Test
    void stampsTheRowEvenWhenNobodyCanBeResolved() {
        response.setCriticalItemPositive(true);
        response.setRecordedByUserId(null);
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_MIDWIFE"))
            .thenReturn(List.of());

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
        assertThat(response.getEscalationLevel()).isEqualTo((short) 1);
        assertThat(response.getLastEscalationAt()).isNotNull();
        // The interval advances; the promise to the patient does not.
        assertThat(response.getNotifiedAt()).isNull();
        verify(responseRepository).save(response);
    }

    @Test
    void aLaterRoundThatReachesSomebodyMakesThePromiseTrue() {
        response.setCriticalItemPositive(true);
        response.setRecordedByUserId(null);
        response.setEscalationLevel((short) 1);
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_MIDWIFE"))
            .thenReturn(List.of());
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_HOSPITAL_ADMIN"))
            .thenReturn(List.of("admin.zongo"));

        service.escalateOverdue();

        verify(notificationService).createNotification(anyString(), eq("admin.zongo"),
            eq(ProScreeningEscalationService.ESCALATION_TYPE));
        assertThat(response.getNotifiedAt()).isNotNull();
    }

    @Test
    void oneBadRowDoesNotStallTheSweep() {
        ProResponse broken = ProResponse.builder().build();
        broken.setId(UUID.randomUUID()); // no hospital/patient — recipients() copes, save() is made to fail
        response.setCriticalItemPositive(true);
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(broken, response));
        when(responseRepository.save(broken)).thenThrow(new IllegalStateException("stale row"));

        int escalated = service.escalateOverdue();

        assertThat(escalated).isEqualTo(1);
        verify(responseRepository).save(response);
    }

    @Test
    void smsOncePerRoundNotOncePerRecipient() {
        response.setCriticalItemPositive(true);
        response.setEscalationLevel((short) 1);
        when(smsService.deliversRealSms()).thenReturn(true);
        when(responseRepository.findCriticalAwaitingEscalation(any(LocalDateTime.class)))
            .thenReturn(List.of(response));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_HOSPITAL_ADMIN"))
            .thenReturn(List.of("admin.a", "admin.b"));

        service.escalateOverdue();

        verify(notificationService, times(3)).createNotification(anyString(), anyString(), anyString());
        verify(smsService, times(1)).send(anyString(), anyString());
    }

    // ── recipients ────────────────────────────────────────────────────

    @Test
    void recipientsDeduplicateWhenTheRecorderIsAlsoThePanelOwner() {
        when(panelAssignmentRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                patientId, hospitalId, PanelRole.PRIMARY_PROVIDER, PanelAssignmentStatus.ACTIVE))
            .thenReturn(Optional.of(assignmentFor("midwife.kone")));

        Set<String> recipients = service.recipients(response, 1);

        assertThat(recipients).containsExactly("midwife.kone");
    }

    @Test
    void recipientsSkipAPanelOwnerWithoutAUserAccount() {
        PanelAssignment orphan = PanelAssignment.builder().providerStaff(Staff.builder().build()).build();
        when(panelAssignmentRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                patientId, hospitalId, PanelRole.CHW, PanelAssignmentStatus.ACTIVE))
            .thenReturn(Optional.of(orphan));

        Set<String> recipients = service.recipients(response, 1);

        assertThat(recipients).containsExactly("midwife.kone");
    }
}
