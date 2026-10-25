package com.example.hms.service.maternity;

import com.example.hms.model.InBasketItem;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D605: unit cover for the escalation worker's guards and recipient rules. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ObstetricAlertEscalationWorkerTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCUMENTER = UUID.randomUUID();

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

    private ObstetricAlertEscalationWorker worker;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);
        worker = new ObstetricAlertEscalationWorker(escalationRepository, inBasketItemRepository,
            onCallScheduleRepository, staffRepository, userRepository, patientRepository,
            notificationService, inBasketService, messageSource, clock, currency);
        when(userRepository.findById(DOCUMENTER)).thenReturn(Optional.of(user("documenter", DOCUMENTER)));
        when(messageSource.getMessage(any(), any(), any())).thenReturn("msg");
        when(staffRepository.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_DOCTOR"))
            .thenReturn(List.of("drA", "documenter"));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_MIDWIFE"))
            .thenReturn(List.of("mwB"));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_NURSE"))
            .thenReturn(List.of("nurseC"));
        when(onCallScheduleRepository.findByHospitalAndWindow(eq(HOSPITAL), any(), any())).thenReturn(List.of());
        when(userRepository.findByUsernameInIgnoreCase(anyList()))
            .thenReturn(List.of(user("drA", UUID.randomUUID())));
    }

    private static User user(String username, UUID id) {
        User u = User.builder().username(username).build();
        u.setId(id);
        return u;
    }

    private static OnCallSchedule shift(String username, boolean staffActive, boolean userActive) {
        Staff staff = Staff.builder().active(staffActive)
            .user(User.builder().username(username).isActive(userActive).build()).build();
        OnCallSchedule s = new OnCallSchedule();
        s.setStaff(staff);
        return s;
    }

    private static ObstetricAlertEscalation row(short level) {
        ObstetricAlertEscalation r = ObstetricAlertEscalation.builder()
            .hospitalId(HOSPITAL).patientId(PATIENT).documentedByUserId(DOCUMENTER)
            .typeLabel("Eclampsia").alertMessage("BP 170/110").escalationLevel(level).build();
        r.setId(UUID.randomUUID());
        return r;
    }

    private static ObstetricAlertEscalationService.UrgentAlert alert(UUID documentedBy) {
        return new ObstetricAlertEscalationService.UrgentAlert(HOSPITAL, PATIENT, "Jane",
            "LABOR", UUID.randomUUID(), "ECLAMPSIA", "Eclampsia", "BP", documentedBy, "text");
    }

    @Test
    void firstNoticeGoesToTheOnCallObstetricTeamOnlyAndSkipsTheDocumenter() {
        when(onCallScheduleRepository.findByHospitalAndWindow(eq(HOSPITAL), any(), any())).thenReturn(List.of(
            shift("DRA", true, true), shift("documenter", true, true), shift("labTech", true, true),
            shift("mwB", false, true), shift("mwB", true, false), new OnCallSchedule()));
        assertThat(worker.firstNoticeRecipients(HOSPITAL, "documenter")).containsExactly("DRA");
    }

    @Test
    void firstNoticeFallsBackToTheWholeTeamWhenNobodyQualifiedIsOnCall() {
        assertThat(worker.firstNoticeRecipients(HOSPITAL, null)).containsExactly("drA", "documenter", "mwB");
        assertThat(worker.reminderRecipients(HOSPITAL)).containsExactly("drA", "documenter", "mwB", "nurseC");
    }

    @Test
    void raiseSavesTheRowAndDeliversDespiteFailingRecipients() {
        ObstetricAlertEscalation saved = row((short) 0);
        when(escalationRepository.save(any())).thenReturn(saved);
        doThrow(new IllegalStateException("x")).when(notificationService).createNotification(any(), any(), eq("drA"), any());
        when(inBasketService.createItemInNewTransaction(any(CreateInBasketItemRequest.class)))
            .thenThrow(new IllegalStateException("y"));
        assertThat(worker.raise(alert(DOCUMENTER))).isEqualTo(saved.getId());
        // QA D665: keyed in the reader's language, the type re-keyed from the source module.
        verify(notificationService).createNotification(eq("labor.alert.notification"),
            org.mockito.ArgumentMatchers.argThat(a -> a.length == 3
                && java.util.Map.of("key", "labor.alert.type.ECLAMPSIA").equals(a[0])
                && "Jane".equals(a[1]) && "BP".equals(a[2])),
            eq("mwB"), eq(ObstetricAlertEscalationWorker.NOTIFICATION_TYPE));
        verify(notificationService, never()).createNotification(any(), any(), eq("documenter"), any());
    }

    @Test
    void raiseWithNobodyToNotifyStillSavesTheRow() {
        when(staffRepository.findActiveUsernamesByHospitalAndRole(any(), any())).thenReturn(List.of());
        when(escalationRepository.save(any())).thenReturn(row((short) 0));
        worker.raise(alert(null));
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    @Test
    void escalateOneSkipsMissingAcknowledgedAndExhaustedAlerts() {
        UUID missing = UUID.randomUUID();
        when(escalationRepository.findById(missing)).thenReturn(Optional.empty());
        assertThat(worker.escalateOne(missing)).isFalse();

        ObstetricAlertEscalation acked = row((short) 0);
        acked.setAcknowledgedAt(LocalDateTime.now());
        when(escalationRepository.findById(acked.getId())).thenReturn(Optional.of(acked));
        assertThat(worker.escalateOne(acked.getId())).isFalse();

        ObstetricAlertEscalation exhausted = row((short) 3);
        when(escalationRepository.findById(exhausted.getId())).thenReturn(Optional.of(exhausted));
        assertThat(worker.escalateOne(exhausted.getId())).isFalse();
        verify(escalationRepository, never()).claimRound(any(), anyShort(), anyShort(), any());
    }

    @Test
    void escalateOneCopiesAnInBasketAcknowledgement() {
        ObstetricAlertEscalation r = row((short) 0);
        when(escalationRepository.findById(r.getId())).thenReturn(Optional.of(r));
        InBasketItem ack = new InBasketItem();
        UUID by = UUID.randomUUID();
        ack.setAcknowledgedAt(LocalDateTime.now());
        ack.setAcknowledgedBy(by);
        when(inBasketItemRepository.findAcknowledgedForReference(r.getId(),
            ObstetricAlertEscalationWorker.REFERENCE_TYPE, DOCUMENTER)).thenReturn(List.of(ack));
        assertThat(worker.escalateOne(r.getId())).isFalse();
        assertThat(r.getAcknowledgedBy()).isEqualTo(by);
        verify(escalationRepository).save(r);
    }

    @Test
    void escalateOneClosesAnAlertTheRecordNoLongerSupports() {
        ObstetricAlertEscalation r = row((short) 0);
        when(escalationRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(currency.noLongerUrgent(r)).thenReturn(true);
        assertThat(worker.escalateOne(r.getId())).isFalse();
        assertThat(r.getAcknowledgedAt()).isNotNull();
        assertThat(r.getAcknowledgedBy()).isNull();
        verify(escalationRepository).save(r);
        verify(escalationRepository, never()).claimRound(any(), anyShort(), anyShort(), any());
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    @Test
    void escalateOneLosesTheClaimToAnotherInstance() {
        ObstetricAlertEscalation r = row((short) 0);
        when(escalationRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(escalationRepository.claimRound(eq(r.getId()), eq((short) 0), eq((short) 1), any())).thenReturn(0);
        assertThat(worker.escalateOne(r.getId())).isFalse();
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    @Test
    void escalateOneRemindsTheDocumenterAndTheWiderTeamOnTheLastRound() {
        ObstetricAlertEscalation r = row((short) 2);
        r.setTypeLabel(null);
        r.setAlertMessage(null);
        when(escalationRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(escalationRepository.claimRound(eq(r.getId()), eq((short) 2), eq((short) 3), any())).thenReturn(1);
        when(patientRepository.findByIdUnscoped(PATIENT)).thenReturn(Optional.of(new Patient()));
        assertThat(worker.escalateOne(r.getId())).isTrue();
        verify(messageSource).getMessage(eq("patient.fallback.generic"), isNull(), any());
        // QA D659: the reminder still names the patient (here the keyed fallback), keyed per reader (D665).
        for (String u : List.of("documenter", "drA", "mwB", "nurseC")) {
            verify(notificationService).createNotification(eq(ObstetricAlertEscalationWorker.REMINDER_KEY),
                org.mockito.ArgumentMatchers.argThat(a -> a.length == 4 && "3".equals(a[0]) && "".equals(a[1])
                    && java.util.Map.of("key", "patient.fallback.generic").equals(a[2]) && "".equals(a[3])),
                eq(u), eq(ObstetricAlertEscalationWorker.ESCALATION_TYPE));
        }
    }

    @Test
    void escalateOneNamesTheKnownPatientInTheReminder() {
        ObstetricAlertEscalation r = row((short) 0);
        when(escalationRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(escalationRepository.claimRound(eq(r.getId()), eq((short) 0), eq((short) 1), any())).thenReturn(1);
        Patient p = new Patient();
        p.setFirstName("Awa");
        p.setLastName("Traore");
        when(patientRepository.findByIdUnscoped(PATIENT)).thenReturn(Optional.of(p));
        assertThat(worker.escalateOne(r.getId())).isTrue();
        verify(notificationService).createNotification(eq(ObstetricAlertEscalationWorker.REMINDER_KEY),
            org.mockito.ArgumentMatchers.argThat(a -> a[2] instanceof String n && n.contains("Awa")),
            eq("drA"), eq(ObstetricAlertEscalationWorker.ESCALATION_TYPE));
    }
}
