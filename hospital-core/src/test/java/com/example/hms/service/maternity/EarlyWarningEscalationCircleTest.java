package com.example.hms.service.maternity;

import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.User;
import com.example.hms.model.labor.ObstetricAlertEscalation;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.repository.ObstetricAlertEscalationRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.NotificationService;
import com.example.hms.service.lab.CriticalResultRecipients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D3862: who an adult's HIGH NEWS2 reaches through the escalation path. The responsible doctor and the visit's
 * clinician (rung by the vital set's page: an item, no second bell), the nurse in charge (an item and a bell), and
 * the covering doctor when no doctor is among them; never the obstetric team. One alert per patient while it is
 * still reminding.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EarlyWarningEscalationCircleTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID RECORDER = UUID.randomUUID();

    @Mock private ObstetricAlertEscalationRepository escalationRepository;
    @Mock private InBasketItemRepository inBasketItemRepository;
    @Mock private OnCallScheduleRepository onCallScheduleRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private NotificationService notificationService;
    @Mock private InBasketService inBasketService;
    @Mock private MessageSource messageSource;
    @Mock private ObstetricAlertCurrency currency;
    @Mock private CriticalResultRecipients clinicalRecipients;
    @Mock private com.example.hms.service.vitals.News2CareTeam careTeam;

    private ObstetricAlertEscalationWorker worker;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);
        worker = new ObstetricAlertEscalationWorker(escalationRepository, inBasketItemRepository,
            onCallScheduleRepository, staffRepository, userRepository, patientRepository,
            notificationService, inBasketService, messageSource, clock, currency);
        worker.setClinicalRecipients(clinicalRecipients);
        worker.setCareTeam(careTeam);
        when(userRepository.findById(RECORDER)).thenReturn(Optional.of(user("nurse_a", RECORDER)));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_DOCTOR"))
            .thenReturn(List.of("dr_a", "dr_b"));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_MIDWIFE"))
            .thenReturn(List.of("mw_a"));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_NURSE"))
            .thenReturn(List.of("nurse_a", "nurse_b"));
        when(careTeam.wardNurses(HOSPITAL, PATIENT)).thenReturn(Set.of("nurse_b"));
        when(careTeam.reminderCircle(HOSPITAL, PATIENT)).thenReturn(Set.of("dr_a", "nurse_b"));
        when(clinicalRecipients.nursesInCharge(HOSPITAL, true)).thenReturn(Set.of("nurse_a", "nurse_b"));
        when(clinicalRecipients.cover(eq(HOSPITAL), any())).thenReturn(Set.of("dr_b"));
        when(onCallScheduleRepository.findByHospitalAndWindow(eq(HOSPITAL), any(), any())).thenReturn(List.of());
        when(escalationRepository.save(any())).thenAnswer(call -> {
            ObstetricAlertEscalation row = call.getArgument(0);
            row.setId(UUID.randomUUID());
            return row;
        });
        when(userRepository.findByUsernameInIgnoreCase(anyList())).thenAnswer(call -> {
            List<String> names = call.getArgument(0);
            return names.stream().map(n -> user(n, UUID.randomUUID())).toList();
        });
    }

    private static User user(String username, UUID id) {
        User u = User.builder().username(username).build();
        u.setId(id);
        return u;
    }

    private static ObstetricAlertEscalationService.UrgentAlert news2(Set<String> rang) {
        return new ObstetricAlertEscalationService.UrgentAlert(HOSPITAL, PATIENT, "Ousmane Test",
            ObstetricAlertEscalationService.SOURCE_NEWS2, UUID.randomUUID(), "NEWS2_HIGH", "NEWS2, élevé",
            "NEWS2 15", RECORDER, "Alerte NEWS2", "news2.alert.msg.NEWS2_HIGH", List.of(15, 15), rang);
    }

    @Test
    void theResponsibleDoctorAndTheNurseInChargeGetACriticalItemNeverTheMidwives() {
        worker.raise(news2(Set.of("nurse_a", "dr_a")));

        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService, atLeastOnce()).createItemInNewTransaction(items.capture());
        assertThat(items.getAllValues()).allSatisfy(item -> {
            assertThat(item.getPriority()).isEqualTo(InBasketPriority.CRITICAL);
            assertThat(item.getTitleKey()).isEqualTo("news2.alert.notification");
            assertThat(item.getReferenceType()).isEqualTo(ObstetricAlertEscalationWorker.REFERENCE_TYPE);
        });
        assertThat(items.getAllValues()).hasSize(2);
        // dr_a was rung by the vital set's page: the item, no second bell. nurse_b, the nurse in charge: a bell too.
        verify(notificationService, never()).createNotification(any(), any(), eq("dr_a"), any());
        verify(notificationService).createNotification(eq("news2.alert.notification"), any(), eq("nurse_b"),
            eq(ObstetricAlertEscalationWorker.EARLY_WARNING_NOTIFICATION_TYPE));
        // The recorder files it and cannot acknowledge it; the midwife is not in an adult's circle.
        verify(notificationService, never()).createNotification(any(), any(), eq("nurse_a"), any());
        verify(notificationService, never()).createNotification(any(), any(), eq("mw_a"), any());
    }

    @Test
    void anAdultsBellsAreNeverLabelledObstetric() {
        assertThat(ObstetricAlertEscalationWorker.noticeType(ObstetricAlertEscalationService.SOURCE_NEWS2, false))
            .isEqualTo("EARLY_WARNING_ALERT");
        assertThat(ObstetricAlertEscalationWorker.noticeType(ObstetricAlertEscalationService.SOURCE_NEWS2, true))
            .isEqualTo("EARLY_WARNING_ALERT_ESCALATION");
        assertThat(ObstetricAlertEscalationWorker.noticeType(ObstetricAlertEscalationService.SOURCE_PARTOGRAPH, true))
            .isEqualTo(ObstetricAlertEscalationWorker.ESCALATION_TYPE);
    }

    @Test
    void aWardThatNamesNoNurseFallsBackToTheNurseInCharge() {
        when(careTeam.wardNurses(HOSPITAL, PATIENT)).thenReturn(Set.of());
        assertThat(worker.firstCircle(news2(Set.of("dr_a")))).containsExactlyInAnyOrder("dr_a", "nurse_a", "nurse_b");
    }

    @Test
    void withNoDoctorInTheCircleTheCoveringDoctorIsAdded() {
        assertThat(worker.firstCircle(news2(Set.of("nurse_a")))).contains("nurse_b", "dr_b").doesNotContain("mw_a");
        assertThat(worker.firstCircle(news2(Set.of("nurse_a", "dr_a")))).containsExactlyInAnyOrder("nurse_a", "dr_a", "nurse_b");
    }

    @Test
    void remindersReachThePatientsCareTeamNotTheWholeHospital() {
        // Review of #1339: the care team (News2CareTeam), never every doctor and nurse of the hospital.
        assertThat(worker.reminderCircle(ObstetricAlertEscalationService.SOURCE_NEWS2, HOSPITAL, PATIENT))
            .containsExactlyInAnyOrder("dr_a", "nurse_b");
        // Every other source keeps the obstetric circle.
        assertThat(worker.reminderCircle(ObstetricAlertEscalationService.SOURCE_PARTOGRAPH, HOSPITAL, PATIENT))
            .contains("mw_a");
    }

    @Test
    void aRepeatedHighReadingFilesNothingWhileTheFirstAlertIsStillReminding() {
        when(escalationRepository.existsOpenForPatient(eq(HOSPITAL), eq(PATIENT),
            eq(ObstetricAlertEscalationService.SOURCE_NEWS2), eq("NEWS2_HIGH"), anyShort())).thenReturn(true);

        assertThat(worker.raise(news2(Set.of("dr_a")))).isNull();

        verify(escalationRepository, never()).save(any());
        verify(inBasketService, never()).createItemInNewTransaction(any());
    }

    @Test
    void anObstetricAlertIsNeverHeldByTheRepeatCheck() {
        ObstetricAlertEscalationService.UrgentAlert partograph = new ObstetricAlertEscalationService.UrgentAlert(
            HOSPITAL, PATIENT, "Awa", ObstetricAlertEscalationService.SOURCE_PARTOGRAPH, UUID.randomUUID(),
            "FETAL_HEART_RATE", "FHR", "FHR 100", RECORDER, "text");
        when(escalationRepository.existsOpenForPatient(any(), any(), any(), any(), anyShort())).thenReturn(true);

        assertThat(worker.raise(partograph)).isNotNull();
    }
}
