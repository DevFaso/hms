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

    /** The fixed clock's « now », as the worker reads it. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 10, 0);

    /**
     * An alert whose first notice went out two days ago and whose last step was taken over a day ago: whatever its
     * level, the next step of the ladder is due (the default minutes: 15, 30, 60, then reminders up to once a day).
     */
    private static ObstetricAlertEscalation row(short level) {
        ObstetricAlertEscalation r = ObstetricAlertEscalation.builder()
            .hospitalId(HOSPITAL).patientId(PATIENT).documentedByUserId(DOCUMENTER)
            .sourceType(ObstetricAlertEscalationService.SOURCE_PARTOGRAPH)
            .typeLabel("Eclampsia").alertMessage("BP 170/110").escalationLevel(level)
            .firstNoticeFiledAt(NOW.minusDays(2)).lastEscalationAt(NOW.minusDays(1).minusMinutes(1)).build();
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
    void reRaisedStillbirthAlertReplacesTheOpenEscalationInsteadOfEscalatingBesideIt() {
        // QA D1801: stillborn -> live -> stillborn before the sweep: the first row was never closed.
        UUID delivery = UUID.randomUUID();
        ObstetricAlertEscalation first = row((short) 0);
        when(escalationRepository.findOpenBySourceAndMessageKey(HOSPITAL,
            ObstetricAlertEscalationService.SOURCE_DELIVERY, delivery, ObstetricAlertCurrency.STILLBIRTH_ALERT_MESSAGE_KEY))
            .thenReturn(List.of(first));
        when(escalationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        worker.raise(stillbirthAlert(delivery, ObstetricAlertCurrency.STILLBIRTH_ALERT_MESSAGE_KEY));
        assertThat(first.getAcknowledgedAt()).isNotNull();
        assertThat(first.getAcknowledgedBy()).isNull();
        // Rex on #1151: the replaced series' open in-basket tasks are removed, so only the current ones remain.
        verify(inBasketItemRepository).deleteOpenByReference(first.getId(), ObstetricAlertEscalationWorker.REFERENCE_TYPE);
    }

    @Test
    void otherDeliveryAlertsAreNeverClosedByARaise() {
        UUID delivery = UUID.randomUUID();
        when(escalationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        worker.raise(stillbirthAlert(delivery, "labor.alert.msg.labor-apgar"));
        verify(escalationRepository, never()).findOpenBySourceAndMessageKey(any(), any(), any(), any());
        verify(inBasketItemRepository, never()).deleteOpenByReference(any(), any());
    }

    private static ObstetricAlertEscalationService.UrgentAlert stillbirthAlert(UUID delivery, String key) {
        return new ObstetricAlertEscalationService.UrgentAlert(HOSPITAL, PATIENT, "Jane",
            ObstetricAlertEscalationService.SOURCE_DELIVERY, delivery, "OTHER", "Other", "Stillbirth", DOCUMENTER,
            "text", key, null);
    }

    @Test
    void raiseWithNobodyToNotifyStillSavesTheRow() {
        when(staffRepository.findActiveUsernamesByHospitalAndRole(any(), any())).thenReturn(List.of());
        when(escalationRepository.save(any())).thenReturn(row((short) 0));
        worker.raise(alert(null));
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    @Test
    void escalateOneSkipsMissingAcknowledgedAndBacklogAlerts() {
        UUID missing = UUID.randomUUID();
        when(escalationRepository.findById(missing)).thenReturn(Optional.empty());
        assertThat(worker.escalateOne(missing)).isFalse();

        ObstetricAlertEscalation acked = row((short) 0);
        acked.setAcknowledgedAt(LocalDateTime.now());
        when(escalationRepository.findById(acked.getId())).thenReturn(Optional.of(acked));
        assertThat(worker.escalateOne(acked.getId())).isFalse();

        // V386: an alert that had used its three rounds under the capped rule is never escalated again.
        ObstetricAlertEscalation backlog = row((short) 3);
        backlog.setLadderBacklog(true);
        when(escalationRepository.findById(backlog.getId())).thenReturn(Optional.of(backlog));
        assertThat(worker.escalateOne(backlog.getId())).isFalse();
        verify(escalationRepository, never()).claimRound(any(), anyShort(), anyShort(), any());
    }

    @Test
    void anAlertWithNoStepDueIsLeftAlone() {
        ObstetricAlertEscalation fresh = row((short) 0);
        fresh.setFirstNoticeFiledAt(NOW.minusMinutes(10));
        fresh.setLastEscalationAt(NOW.minusMinutes(10));
        when(escalationRepository.findById(fresh.getId())).thenReturn(Optional.of(fresh));
        assertThat(worker.escalateOne(fresh.getId())).isFalse();
        verify(escalationRepository, never()).claimRound(any(), anyShort(), anyShort(), any());
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    // ── Scorecard K4: the one ladder ──────────────────────────────────────────────────

    private com.example.hms.service.escalation.UrgentEscalationSupport ladder(
            com.example.hms.service.lab.CriticalResultEscalationLadder.Step step) {
        com.example.hms.service.escalation.UrgentEscalationSupport ladder =
            org.mockito.Mockito.mock(com.example.hms.service.escalation.UrgentEscalationSupport.class);
        when(ladder.due(org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any()))
            .thenReturn(Optional.ofNullable(step));
        when(ladder.seniors(any(), any(), any())).thenReturn(new java.util.LinkedHashSet<>());
        when(ladder.addedAt(any(), any(), any())).thenReturn(java.util.Set.of());
        when(ladder.cover(any(), any())).thenReturn(java.util.Set.of());
        when(ladder.medicalLead(any())).thenReturn(java.util.Set.of());
        when(ladder.biologists(any())).thenReturn(java.util.Set.of());
        worker.setLadder(ladder);
        return ladder;
    }

    @Test
    void noCountOfStepsEndsAnAlert() {
        // Only an acknowledgement ends an alert: step 41 is taken like step 1.
        com.example.hms.service.escalation.UrgentEscalationSupport ladder =
            ladder(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.REMINDER);
        ObstetricAlertEscalation r = row((short) 40);
        when(escalationRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(escalationRepository.claimRound(eq(r.getId()), eq((short) 40), eq((short) 41), any())).thenReturn(1);

        assertThat(worker.escalateOne(r.getId())).isTrue();

        verify(notificationService).createNotification(eq(ObstetricAlertEscalationWorker.UNRESOLVED_KEY),
            org.mockito.ArgumentMatchers.argThat(a -> a.length == 4 && Long.valueOf(2880L).equals(a[3])),
            eq("drA"), eq(ObstetricAlertEscalationWorker.ESCALATION_TYPE));
        verify(ladder).openIncident(eq(com.example.hms.model.UrgentAlertIncident.SOURCE_URGENT_ALERT), eq(r.getId()),
            eq(HOSPITAL), eq(NOW), eq(2880L), eq(3));
    }

    @Test
    void theStepsAddTheCoveringClinicianThenTheMedecinChefAndTheLastOneOpensTheIncident() {
        com.example.hms.service.escalation.UrgentEscalationSupport ladder =
            ladder(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER);
        when(ladder.seniors(HOSPITAL, com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER, "documenter"))
            .thenReturn(new java.util.LinkedHashSet<>(List.of("drCover")));
        when(ladder.addedAt(HOSPITAL, com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER, "documenter"))
            .thenReturn(java.util.Set.of("drCover"));
        Patient awa = new Patient();
        awa.setFirstName("Awa");
        awa.setLastName("Traore");
        when(patientRepository.findByIdUnscoped(PATIENT)).thenReturn(Optional.of(awa));
        ObstetricAlertEscalation r = row((short) 0);
        when(escalationRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(escalationRepository.claimRound(eq(r.getId()), eq((short) 0), eq((short) 1), any())).thenReturn(1);

        assertThat(worker.escalateOne(r.getId())).isTrue();

        // The alert's own circle keeps its reminder, and the covering clinician is added to it.
        for (String u : List.of("drA", "mwB", "nurseC", "drCover")) {
            verify(notificationService).createNotification(eq(ObstetricAlertEscalationWorker.REMINDER_KEY),
                org.mockito.ArgumentMatchers.any(), eq(u), eq(ObstetricAlertEscalationWorker.ESCALATION_TYPE));
        }
        // The clinician the step adds gets the staff SMS: initials, nothing clinical.
        verify(ladder).smsAfterCommit(eq(java.util.Set.of("drCover")), eq("AT"), eq(r.getId()));
        verify(ladder, never()).openIncident(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyInt());

        // The last step: « non résolue », with the médecin-chef, and the incident.
        when(ladder.due(org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any()))
            .thenReturn(Optional.of(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.UNRESOLVED));
        when(ladder.seniors(HOSPITAL, com.example.hms.service.lab.CriticalResultEscalationLadder.Step.UNRESOLVED,
            "documenter")).thenReturn(new java.util.LinkedHashSet<>(List.of("drCover", "drChef")));
        ObstetricAlertEscalation last = row((short) 2);
        when(escalationRepository.findById(last.getId())).thenReturn(Optional.of(last));
        when(escalationRepository.claimRound(eq(last.getId()), eq((short) 2), eq((short) 3), any())).thenReturn(1);

        assertThat(worker.escalateOne(last.getId())).isTrue();

        verify(notificationService).createNotification(eq(ObstetricAlertEscalationWorker.UNRESOLVED_KEY),
            org.mockito.ArgumentMatchers.any(), eq("drChef"), eq(ObstetricAlertEscalationWorker.ESCALATION_TYPE));
        verify(ladder).openIncident(eq(com.example.hms.model.UrgentAlertIncident.SOURCE_URGENT_ALERT), eq(last.getId()),
            eq(HOSPITAL), eq(NOW), org.mockito.ArgumentMatchers.anyLong(), eq(5));
    }

    @Test
    void anAcknowledgementOrARecordThatNoLongerSupportsTheAlertClosesItsIncident() {
        com.example.hms.service.escalation.UrgentEscalationSupport ladder =
            ladder(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.REMINDER);
        ObstetricAlertEscalation acked = row((short) 5);
        when(escalationRepository.findById(acked.getId())).thenReturn(Optional.of(acked));
        InBasketItem ack = new InBasketItem();
        UUID by = UUID.randomUUID();
        ack.setAcknowledgedAt(NOW.minusMinutes(1));
        ack.setAcknowledgedBy(by);
        when(inBasketItemRepository.findAcknowledgedForReference(acked.getId(),
            ObstetricAlertEscalationWorker.REFERENCE_TYPE, DOCUMENTER)).thenReturn(List.of(ack));

        assertThat(worker.escalateOne(acked.getId())).isFalse();

        verify(ladder).closeIncident(com.example.hms.model.UrgentAlertIncident.SOURCE_URGENT_ALERT, acked.getId(),
            com.example.hms.model.UrgentAlertIncident.RESOLVED_ACKNOWLEDGED, by, NOW.minusMinutes(1));

        ObstetricAlertEscalation withdrawn = row((short) 5);
        when(escalationRepository.findById(withdrawn.getId())).thenReturn(Optional.of(withdrawn));
        when(currency.noLongerUrgent(withdrawn)).thenReturn(true);

        assertThat(worker.escalateOne(withdrawn.getId())).isFalse();

        verify(ladder).closeIncident(com.example.hms.model.UrgentAlertIncident.SOURCE_URGENT_ALERT, withdrawn.getId(),
            com.example.hms.model.UrgentAlertIncident.RESOLVED_NO_LONGER_URGENT, null, NOW);
    }

    @Test
    void aSecondUrgentReadingAfterTheFirstWasAcknowledgedIsFiled_andTheAnsweredRowIsClosedWithItsAcknowledgement() {
        // Review of #1370 (F1): the doctor acknowledged the first urgent-hypertension item; a second urgent reading
        // came before the next sweep. It was held as a repeat, then the sweep closed the old row: nobody was told.
        ObstetricAlertEscalation answered = row((short) 0);
        answered.setSourceType(ObstetricAlertEscalationService.SOURCE_URGENT_HYPERTENSION);
        answered.setAlertType("URGENT_HYPERTENSION");
        when(escalationRepository.findOpenForPatient(HOSPITAL, PATIENT,
            ObstetricAlertEscalationService.SOURCE_URGENT_HYPERTENSION, "URGENT_HYPERTENSION"))
            .thenReturn(List.of(answered));
        InBasketItem ack = new InBasketItem();
        UUID doctor = UUID.randomUUID();
        ack.setAcknowledgedAt(NOW.minusMinutes(3));
        ack.setAcknowledgedBy(doctor);
        when(inBasketItemRepository.findAcknowledgedForReference(answered.getId(),
            ObstetricAlertEscalationWorker.REFERENCE_TYPE, DOCUMENTER)).thenReturn(List.of(ack));
        when(escalationRepository.save(any())).thenAnswer(inv -> {
            ObstetricAlertEscalation saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });

        assertThat(worker.file(general(ObstetricAlertEscalationService.SOURCE_URGENT_HYPERTENSION,
            "URGENT_HYPERTENSION", List.of("drA")), false)).as("the new reading is filed").isNotNull();
        assertThat(answered.getAcknowledgedAt()).isEqualTo(NOW.minusMinutes(3));
        assertThat(answered.getAcknowledgedBy()).isEqualTo(doctor);

        // Not yet acknowledged: the new reading is still a repeat of an alert that is reminding.
        ObstetricAlertEscalation reminding = row((short) 0);
        when(escalationRepository.findOpenForPatient(HOSPITAL, PATIENT,
            ObstetricAlertEscalationService.SOURCE_URGENT_HYPERTENSION, "URGENT_HYPERTENSION"))
            .thenReturn(List.of(reminding));
        assertThat(worker.file(general(ObstetricAlertEscalationService.SOURCE_URGENT_HYPERTENSION,
            "URGENT_HYPERTENSION", List.of("drA")), false)).isNull();
        assertThat(reminding.getAcknowledgedAt()).isNull();
    }

    @Test
    void anOwedFirstNoticeIsNotSentWhenTheRecordNoLongerSupportsTheAlert() {
        // Review of #1370 (F2): a culture corrected to no growth while its first notice was owed.
        ObstetricAlertEscalation owed = row((short) 0);
        owed.setFirstNoticeFiledAt(null);
        owed.setSourceType(ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE);
        owed.setAlertType("POSITIVE_CULTURE");
        owed.setSourceId(UUID.randomUUID());
        when(escalationRepository.findById(owed.getId())).thenReturn(Optional.of(owed));
        when(escalationRepository.claimFirstNotice(eq(owed.getId()), any())).thenReturn(1);
        when(currency.noLongerUrgent(owed)).thenReturn(true);

        assertThat(worker.escalateOne(owed.getId())).isFalse();

        verify(notificationService, never()).createNotification(any(), any(), any(), any());
        verify(inBasketService, never()).createItemInNewTransaction(any());
        verify(inBasketService, never()).createItem(any());
        assertThat(owed.getAcknowledgedAt()).as("closed by the record").isEqualTo(NOW);
        assertThat(owed.getAcknowledgedBy()).isNull();

        // The delivery that follows the commit re-reads the record too.
        owed.setAcknowledgedAt(null);
        assertThat(worker.deliverFirstNotice(owed.getId(), general(
            ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE, "POSITIVE_CULTURE", List.of("drA")))).isFalse();
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
        verify(inBasketService, never()).createItemInNewTransaction(any());
        assertThat(owed.getAcknowledgedAt()).isEqualTo(NOW);
    }

    // ── Scorecard K4: the first notice is owed until it is delivered ───────────────────

    @Test
    void filingWritesTheRowWithItsFirstNoticeOwed() {
        when(escalationRepository.save(any())).thenAnswer(inv -> {
            ObstetricAlertEscalation saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        assertThat(worker.file(alert(DOCUMENTER), false)).isNotNull();

        org.mockito.ArgumentCaptor<ObstetricAlertEscalation> row =
            org.mockito.ArgumentCaptor.forClass(ObstetricAlertEscalation.class);
        verify(escalationRepository).save(row.capture());
        assertThat(row.getValue().getFirstNoticeFiledAt()).as("owed until delivered").isNull();
        // Filing tells nobody: the delivery follows the commit.
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
        verify(inBasketService, never()).createItemInNewTransaction(any());
    }

    @Test
    void theDeliveryClaimsTheOwedNoticeOnceAndTheSweepDeliversARowLeftOwed() {
        ObstetricAlertEscalation owed = row((short) 0);
        owed.setFirstNoticeFiledAt(null);
        owed.setSourceType(ObstetricAlertEscalationService.SOURCE_PARTOGRAPH);
        owed.setAlertType("ECLAMPSIA");
        when(escalationRepository.findById(owed.getId())).thenReturn(Optional.of(owed));

        // Somebody else holds the claim: nothing is sent twice.
        when(escalationRepository.claimFirstNotice(eq(owed.getId()), any())).thenReturn(0);
        assertThat(worker.deliverFirstNotice(owed.getId(), alert(DOCUMENTER))).isFalse();
        assertThat(worker.escalateOne(owed.getId())).isFalse();
        verify(notificationService, never()).createNotification(any(), any(), any(), any());

        // The delivery after the commit never ran: the sweep reads the alert back from the row and delivers it.
        when(escalationRepository.claimFirstNotice(eq(owed.getId()), any())).thenReturn(1);
        assertThat(worker.escalateOne(owed.getId())).isTrue();
        verify(notificationService).createNotification(eq("labor.alert.notification"),
            org.mockito.ArgumentMatchers.any(), eq("drA"), eq(ObstetricAlertEscalationWorker.NOTIFICATION_TYPE));
        verify(notificationService).createNotification(eq("labor.alert.notification"),
            org.mockito.ArgumentMatchers.any(), eq("mwB"), eq(ObstetricAlertEscalationWorker.NOTIFICATION_TYPE));
        verify(inBasketService).createItemInNewTransaction(any(CreateInBasketItemRequest.class));
        // The first notice is not a step: the ladder starts from it.
        verify(escalationRepository, never()).claimRound(any(), anyShort(), anyShort(), any());
    }

    @Test
    void aFirstNoticeNobodyReceivedStaysOwed() {
        ObstetricAlertEscalation owed = row((short) 0);
        owed.setFirstNoticeFiledAt(null);
        when(escalationRepository.findById(owed.getId())).thenReturn(Optional.of(owed));
        when(escalationRepository.claimFirstNotice(eq(owed.getId()), any())).thenReturn(1);
        doThrow(new IllegalStateException("bus down")).when(notificationService)
            .createNotification(any(), any(), any(), any());
        when(inBasketService.createItemInNewTransaction(any(CreateInBasketItemRequest.class)))
            .thenThrow(new IllegalStateException("db down"));

        // The exception rolls back the transaction, and the claim with it: the row is still owed at the next sweep.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> worker.deliverFirstNotice(owed.getId(), alert(DOCUMENTER)))
            .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> worker.escalateOne(owed.getId()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStepNobodyReceivedIsGivenBack() {
        ObstetricAlertEscalation r = row((short) 1);
        when(escalationRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(escalationRepository.claimRound(eq(r.getId()), eq((short) 1), eq((short) 2), any())).thenReturn(1);
        doThrow(new IllegalStateException("bus down")).when(notificationService)
            .createNotification(any(), any(), any(), any());
        when(inBasketService.createItemInNewTransaction(any(CreateInBasketItemRequest.class)))
            .thenThrow(new IllegalStateException("db down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> worker.escalateOne(r.getId()))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── Scorecard K4: the sources that are not obstetric ───────────────────────────────

    private static ObstetricAlertEscalationService.UrgentAlert general(String sourceType, String alertType,
                                                                       List<String> named) {
        return new ObstetricAlertEscalationService.UrgentAlert(HOSPITAL, PATIENT, "Jane", sourceType,
            UUID.randomUUID(), alertType, "label", "sentence", DOCUMENTER, "title").withNamedRecipients(named);
    }

    @Test
    void aSevereTransfusionReactionReachesTheRequesterAndTheCoveringPhysicianAndFilesTheHaemovigilanceTask() {
        com.example.hms.service.escalation.UrgentEscalationSupport ladder = ladder(null);
        when(ladder.cover(HOSPITAL, "documenter")).thenReturn(java.util.Set.of("drOnCall"));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_LAB_DIRECTOR"))
            .thenReturn(List.of("labDirector"));
        when(userRepository.findByUsernameInIgnoreCase(List.of("labdirector")))
            .thenReturn(List.of(user("labDirector", UUID.randomUUID())));
        when(escalationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ObstetricAlertEscalationService.UrgentAlert reaction = general(
            ObstetricAlertEscalationService.SOURCE_TRANSFUSION_REACTION, "SEVERE_TRANSFUSION_REACTION", List.of("drA"));

        assertThat(worker.firstCircle(reaction)).containsExactly("drA", "drOnCall");
        worker.raise(reaction);

        for (String clinician : List.of("drA", "drOnCall")) {
            verify(notificationService).createNotification(eq("urgentAlert.notification"),
                org.mockito.ArgumentMatchers.argThat(a -> java.util.Map.of("key",
                    "urgentAlert.type.SEVERE_TRANSFUSION_REACTION").equals(a[0])),
                eq(clinician), eq(ObstetricAlertEscalationWorker.URGENT_NOTIFICATION_TYPE));
        }
        // Never the obstetric team: a transfusion reaction is not a midwife's alert.
        verify(notificationService, never()).createNotification(any(), any(), eq("mwB"), any());
        // The laboratory director's task, filed with the first notice.
        verify(inBasketService).createItem(org.mockito.ArgumentMatchers.argThat(item ->
            ObstetricAlertEscalationWorker.HAEMOVIGILANCE_REFERENCE_TYPE.equals(item.getReferenceType())
                && item.getItemType() == com.example.hms.enums.InBasketItemType.TASK
                && ObstetricAlertEscalationWorker.HAEMOVIGILANCE_TITLE_KEY.equals(item.getTitleKey())));
    }

    @Test
    void aRecordsOwnAlertIsFiledOnceWhileItIsOpen() {
        ObstetricAlertEscalationService.UrgentAlert culture = general(
            ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE, "POSITIVE_CULTURE", List.of("drOrder"));
        when(escalationRepository.findOpenForSource(ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE,
            culture.sourceId())).thenReturn(List.of(row((short) 0)));

        assertThat(worker.file(culture, false)).isNull();

        verify(escalationRepository, never()).save(any());
    }

    @Test
    void aPositiveCultureWithNoOrdererGoesToTheCoveringClinician() {
        com.example.hms.service.escalation.UrgentEscalationSupport ladder = ladder(null);
        when(ladder.cover(HOSPITAL, "documenter")).thenReturn(java.util.Set.of("drOnCall"));

        assertThat(worker.firstCircle(general(ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE,
            "POSITIVE_CULTURE", List.of("drA")))).containsExactly("drA");
        assertThat(worker.firstCircle(general(ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE,
            "POSITIVE_CULTURE", List.of()))).containsExactly("drOnCall");
        // The alert names the patient: an orderer who no longer holds a role at this hospital is not told.
        assertThat(worker.firstCircle(general(ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE,
            "POSITIVE_CULTURE", List.of("drGone")))).containsExactly("drOnCall");
        // Nor at a later step, whether the module named them or rang them at the start.
        ObstetricAlertEscalation later = row((short) 0);
        later.setSourceType(ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE);
        later.setNamedRecipients(List.of("drGone", "drA"));
        later.setAlreadyRang(List.of("drGone", "drA"));
        when(ladder.seniors(HOSPITAL, com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER, "documenter"))
            .thenReturn(new java.util.LinkedHashSet<>(List.of("drOnCall")));
        assertThat(worker.stepCircle(later, com.example.hms.service.lab.CriticalResultEscalationLadder.Step.COVER,
            "documenter")).containsExactly("drA", "drOnCall");
        // The person who reported it cannot be its only reader.
        assertThat(worker.firstCircle(general(ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE,
            "POSITIVE_CULTURE", List.of("documenter")))).containsExactly("documenter", "drOnCall");
    }

    @Test
    void anOverdueCountersignatureKeepsEscalatingUntilItIsSignedWhateverIsAcknowledged() {
        com.example.hms.service.escalation.UrgentEscalationSupport ladder =
            ladder(com.example.hms.service.lab.CriticalResultEscalationLadder.Step.REMINDER);
        when(ladder.seniors(any(), any(), any())).thenReturn(new java.util.LinkedHashSet<>(List.of("drChef")));
        when(ladder.biologists(HOSPITAL)).thenReturn(java.util.Set.of("labDirector"));
        when(staffRepository.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_LAB_DIRECTOR"))
            .thenReturn(List.of("labDirector"));
        ObstetricAlertEscalation r = row((short) 7);
        r.setSourceType(ObstetricAlertEscalationService.SOURCE_INCOMPATIBLE_COUNTERSIGN);
        r.setAlertType("INCOMPATIBLE_COUNTERSIGN_OVERDUE");
        r.setNamedRecipients(List.of("labDirector"));
        when(escalationRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(escalationRepository.claimRound(eq(r.getId()), eq((short) 7), eq((short) 8), any())).thenReturn(1);
        InBasketItem ack = new InBasketItem();
        ack.setAcknowledgedAt(NOW.minusMinutes(1));
        ack.setAcknowledgedBy(UUID.randomUUID());
        when(inBasketItemRepository.findAcknowledgedForReference(any(), any(), any())).thenReturn(List.of(ack));

        // Somebody acknowledged the item: the release is still not countersigned, so the ladder goes on.
        assertThat(worker.escalateOne(r.getId())).isTrue();
        assertThat(r.getAcknowledgedAt()).isNull();
        assertThat(worker.stepCircle(r, com.example.hms.service.lab.CriticalResultEscalationLadder.Step.REMINDER,
            "documenter")).containsExactlyInAnyOrder("labDirector", "drChef");
        verify(notificationService).createNotification(eq(ObstetricAlertEscalationWorker.UNRESOLVED_KEY),
            org.mockito.ArgumentMatchers.any(), eq("drChef"),
            eq(ObstetricAlertEscalationWorker.URGENT_ESCALATION_TYPE));

        // Signed: the record closes it.
        ObstetricAlertEscalation signed = row((short) 7);
        signed.setSourceType(ObstetricAlertEscalationService.SOURCE_INCOMPATIBLE_COUNTERSIGN);
        signed.setAlertType("INCOMPATIBLE_COUNTERSIGN_OVERDUE");
        when(escalationRepository.findById(signed.getId())).thenReturn(Optional.of(signed));
        when(currency.noLongerUrgent(signed)).thenReturn(true);
        assertThat(worker.escalateOne(signed.getId())).isFalse();
        assertThat(signed.getAcknowledgedAt()).isEqualTo(NOW);
    }

    @Test
    void withNoLaboratoryDirectorAnOverdueCountersignatureStartsWithTheMedecinChef() {
        com.example.hms.service.escalation.UrgentEscalationSupport ladder = ladder(null);
        when(ladder.medicalLead(HOSPITAL)).thenReturn(java.util.Set.of("drChef"));

        assertThat(worker.firstCircle(general(ObstetricAlertEscalationService.SOURCE_O_POSITIVE_COUNTERSIGN,
            "O_POSITIVE_COUNTERSIGN_OVERDUE", List.of()))).containsExactly("drChef");
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
        verify(inBasketItemRepository).deleteOpenByReference(r.getId(), ObstetricAlertEscalationWorker.REFERENCE_TYPE);
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
    void escalateOneRemindsTheDocumenterAndTheWiderTeam() {
        ObstetricAlertEscalation r = row((short) 1);
        r.setTypeLabel(null);
        r.setAlertMessage(null);
        when(escalationRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(escalationRepository.claimRound(eq(r.getId()), eq((short) 1), eq((short) 2), any())).thenReturn(1);
        when(patientRepository.findByIdUnscoped(PATIENT)).thenReturn(Optional.of(new Patient()));
        assertThat(worker.escalateOne(r.getId())).isTrue();
        verify(messageSource).getMessage(eq("patient.fallback.generic"), isNull(), any());
        // QA D659: the reminder still names the patient (here the keyed fallback), keyed per reader (D665).
        // QA D2582 (r65): the documenter's reminder never sends her to acknowledge in an in-basket she has no item in.
        verify(notificationService).createNotification(eq(ObstetricAlertEscalationWorker.DOCUMENTER_REMINDER_KEY),
            org.mockito.ArgumentMatchers.any(), eq("documenter"), eq(ObstetricAlertEscalationWorker.ESCALATION_TYPE));
        verify(notificationService, never()).createNotification(
            eq(ObstetricAlertEscalationWorker.REMINDER_KEY), org.mockito.ArgumentMatchers.any(), eq("documenter"),
            org.mockito.ArgumentMatchers.any());
        for (String u : List.of("drA", "mwB", "nurseC")) {
            verify(notificationService).createNotification(eq(ObstetricAlertEscalationWorker.REMINDER_KEY),
                org.mockito.ArgumentMatchers.argThat(a -> a.length == 4 && "2".equals(a[0]) && "".equals(a[1])
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

    // ── Policy rule 2 (2026-11-20, provisional): a newborn's danger sign always reaches a physician ──

    @Test
    void policyRule2_aNewbornDangerSignAlwaysReachesAPhysician() {
        // Only a midwife in the first circle: the hospital's doctors join it.
        assertThat(worker.withAPhysician(new java.util.LinkedHashSet<>(List.of("mwB")), HOSPITAL))
            .containsExactly("mwB", "drA", "documenter");
        // A doctor is already in it (any case): nothing is added.
        assertThat(worker.withAPhysician(new java.util.LinkedHashSet<>(List.of("DRA", "mwB")), HOSPITAL))
            .containsExactly("DRA", "mwB");
        // A hospital with no doctor keeps the circle it has.
        when(staffRepository.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_DOCTOR")).thenReturn(List.of());
        assertThat(worker.withAPhysician(new java.util.LinkedHashSet<>(List.of("mwB")), HOSPITAL))
            .containsExactly("mwB");
    }

    @Test
    void policyRule2_raisingANewbornAlertWithOnlyAMidwifeOnCallNotifiesTheDoctorToo_aLabourAlertDoesNot() {
        when(onCallScheduleRepository.findByHospitalAndWindow(eq(HOSPITAL), any(), any()))
            .thenReturn(List.of(shift("mwB", true, true)));
        when(escalationRepository.save(any())).thenReturn(row((short) 0));

        worker.raise(alert(DOCUMENTER));
        verify(notificationService, never()).createNotification(any(), any(), eq("drA"), any());
        verify(notificationService).createNotification(any(), any(), eq("mwB"), any());

        worker.raise(new ObstetricAlertEscalationService.UrgentAlert(HOSPITAL, PATIENT, "Bébé",
            ObstetricAlertEscalationService.SOURCE_NEWBORN, UUID.randomUUID(), "DANGER_SIGN", "Danger sign",
            "Convulsions", DOCUMENTER, "text"));
        verify(notificationService).createNotification(any(), any(), eq("drA"), any());
    }
}
