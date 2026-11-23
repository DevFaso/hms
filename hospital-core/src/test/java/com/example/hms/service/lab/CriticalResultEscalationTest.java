package com.example.hms.service.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.EncounterType;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.CriticalResultIncident;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Notification;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.CriticalResultIncidentRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.CriticalValueNotificationService;
import com.example.hms.service.SmsService;
import com.example.hms.service.lab.CriticalResultEscalationLadder.Minutes;
import com.example.hms.service.lab.CriticalResultEscalationLadder.Step;
import net.javacrumbs.shedlock.core.LockAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA r70 scorecard C5, provisional rule CRITICAL_RESULT_ESCALATION: T0 the prescriber and the nurse in charge
 * (an absent prescriber is skipped), +15 the cover, +30 the médecin-chef and the biologist, +60 the unresolved
 * alert and its incident, then reminders with no cap; no administrator ever receives the value; only a
 * clinician's acknowledgement (name, role, time, channel) ends it.
 */
class CriticalResultEscalationTest {

    private static final String ESCALATION = "CRITICAL_LAB_RESULT_ESCALATION";

    private final com.example.hms.service.RenderingNotificationService notifications =
        mock(com.example.hms.service.RenderingNotificationService.class, org.mockito.Answers.CALLS_REAL_METHODS);
    private final SmsService sms = mock(SmsService.class);
    private final LabResultRepository results = mock(LabResultRepository.class);
    private final LabResultMapper mapper = mock(LabResultMapper.class);
    private final CriticalResultRecipients recipients = mock(CriticalResultRecipients.class);
    private final CriticalResultIncidentRepository incidents = mock(CriticalResultIncidentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private CriticalValueNotificationService service;

    private LabResult result;
    private Hospital hospital;
    private Staff prescriber;

    @BeforeEach
    void setUp() {
        LockAssert.TestHelper.makeAllAssertsPass(true);
        service = new CriticalValueNotificationService(notifications, sms, results, mapper,
            mock(PlatformTransactionManager.class), com.example.hms.i18n.TestMessageSources.bundles());
        service.setEscalationCollaborators(recipients, incidents, users);

        hospital = Hospital.builder().name("Clinique").code("CLI").build();
        hospital.setId(UUID.randomUUID());
        User doctor = new User();
        doctor.setId(UUID.randomUUID());
        doctor.setUsername("dr.diallo");
        prescriber = Staff.builder().build();
        prescriber.setId(UUID.randomUUID());
        prescriber.setUser(doctor);
        Patient patient = new Patient();
        patient.setFirstName("Bea");
        patient.setLastName("Ward");
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName("Potassium");
        Encounter stay = new Encounter();
        stay.setEncounterType(EncounterType.INPATIENT);
        LabOrder order = new LabOrder();
        order.setOrderingStaff(prescriber);
        order.setPatient(patient);
        order.setLabTestDefinition(definition);
        order.setHospital(hospital);
        order.setEncounter(stay);
        result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue("7.1");
        result.setResultUnit("mmol/L");
        result.setReleased(true);
        result.setAbnormalFlag(AbnormalFlag.CRITICAL);

        lenient().when(notifications.created(anyString(), anyString(), anyString())).thenReturn(new Notification());
        when(sms.deliversRealSms()).thenReturn(false);
        when(results.findCriticalUnacknowledged()).thenReturn(List.of(result));
        when(recipients.nursesInCharge(eq(hospital.getId()), anyBoolean())).thenReturn(Set.of("inf.garde"));
        when(recipients.cover(hospital.getId(), "dr.diallo")).thenReturn(Set.of("dr.garde"));
        when(recipients.medicalLead(hospital)).thenReturn(Set.of("dr.chef"));
        when(recipients.biologists(hospital.getId())).thenReturn(Set.of("bio.kabore"));
        when(recipients.administrators(hospital.getId())).thenReturn(Set.of("admin.sawadogo"));
        when(incidents.findByLabResultIdAndResolvedAtIsNull(any())).thenReturn(Optional.empty());
        // The sweep writes its stamps by conditional UPDATE: here the result plays the row.
        com.example.hms.testsupport.CriticalResultRows.of(results).with(result);
    }

    private void told(String username, String type) {
        verify(notifications).created(anyString(), eq(username), eq(type));
    }

    private void notTold(String username) {
        verify(notifications, never()).created(anyString(), eq(username), anyString());
    }

    private void minutesAgo(int minutes, int level) {
        result.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(minutes));
        result.setCriticalEscalationLevel((short) level);
    }

    // ── The ladder itself ───────────────────────────────────────────────

    @Test
    @DisplayName("the default steps are 15, 30 and 60 minutes, and nothing is due before them")
    void theStepsOfTheLadder() {
        LocalDateTime t0 = LocalDateTime.of(2026, 11, 22, 10, 0);
        Minutes m = Minutes.DEFAULT;
        assertThat(m).isEqualTo(new Minutes(15, 30, 60));
        assertThat(CriticalResultEscalationLadder.due(0, t0, null, m, t0.plusMinutes(14))).isEmpty();
        assertThat(CriticalResultEscalationLadder.due(0, t0, null, m, t0.plusMinutes(15))).contains(Step.COVER);
        assertThat(CriticalResultEscalationLadder.due(1, t0, t0.plusMinutes(15), m, t0.plusMinutes(29))).isEmpty();
        assertThat(CriticalResultEscalationLadder.due(1, t0, t0.plusMinutes(15), m, t0.plusMinutes(30))).contains(Step.MEDICAL_LEAD);
        assertThat(CriticalResultEscalationLadder.due(2, t0, t0.plusMinutes(30), m, t0.plusMinutes(59))).isEmpty();
        assertThat(CriticalResultEscalationLadder.due(2, t0, t0.plusMinutes(30), m, t0.plusMinutes(60))).contains(Step.UNRESOLVED);
    }

    @Test
    @DisplayName("there is no round cap: after the final alert a reminder is due at the last interval, at any level")
    void noRoundCapEver() {
        LocalDateTime t0 = LocalDateTime.of(2026, 11, 22, 10, 0);
        Minutes m = Minutes.DEFAULT;
        LocalDateTime last = t0.plusMinutes(60);
        assertThat(CriticalResultEscalationLadder.due(3, t0, last, m, last.plusMinutes(29))).isEmpty();
        assertThat(CriticalResultEscalationLadder.due(3, t0, last, m, last.plusMinutes(30))).contains(Step.REMINDER);
        // Reminders slow down (30, 60, 120 … minutes) to once a day, and never stop, whatever the level.
        assertThat(m.reminderEveryAt(3)).isEqualTo(30);
        assertThat(m.reminderEveryAt(4)).isEqualTo(60);
        assertThat(m.reminderEveryAt(5)).isEqualTo(120);
        for (int level : new int[] {9, 12, 13, 200, Short.MAX_VALUE}) {
            assertThat(m.reminderEveryAt(level)).as("level " + level).isEqualTo(24 * 60);
            assertThat(CriticalResultEscalationLadder.due(level, t0, last, m, last.plusMinutes(24 * 60 - 1)))
                .as("level " + level).isEmpty();
            assertThat(CriticalResultEscalationLadder.due(level, t0, last, m, last.plusMinutes(24 * 60)))
                .as("level " + level).contains(Step.REMINDER);
        }
    }

    @Test
    @DisplayName("a hospital's own minutes are used when they are a valid, increasing set; otherwise the defaults")
    void perHospitalMinutes() {
        assertThat(Minutes.of(null, null, null)).isEqualTo(Minutes.DEFAULT);
        assertThat(Minutes.of(10, 20, 45)).isEqualTo(new Minutes(10, 20, 45));
        assertThat(Minutes.of(10, null, null)).isEqualTo(new Minutes(10, 30, 60));
        // Not increasing, under five minutes, over a day: the whole set falls back, never a ladder with no step.
        assertThat(Minutes.of(30, 20, 60)).isEqualTo(Minutes.DEFAULT);
        assertThat(Minutes.of(1, 30, 60)).isEqualTo(Minutes.DEFAULT);
        assertThat(Minutes.of(15, 30, 100_000)).isEqualTo(Minutes.DEFAULT);
        assertThat(new Minutes(15, 30, 60).reminderEvery()).isEqualTo(30);
    }

    // ── T0 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T0: the prescriber and the nurse in charge, nobody else")
    void t0_thePrescriberAndTheNurseInCharge() {
        service.notifyIfCritical(result);

        told("dr.diallo", "CRITICAL_LAB_RESULT");
        told("inf.garde", "CRITICAL_LAB_RESULT");
        notTold("dr.garde");
        notTold("admin.sawadogo");
        assertThat(result.getCriticalNotifiedAt()).isNotNull();
        assertThat(result.getCriticalEscalationLevel()).isZero();
    }

    @Test
    @DisplayName("T0: a prescriber marked absent is skipped: the cover is told at once")
    void t0_anAbsentPrescriberIsSkipped() {
        when(recipients.prescriberAbsent(prescriber)).thenReturn(true);

        service.notifyIfCritical(result);

        told("dr.garde", ESCALATION);
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 1);
        notTold("dr.chef");
        notTold("admin.sawadogo");
    }

    // ── The steps ───────────────────────────────────────────────────────

    @Test
    @DisplayName("+15 min: the covering doctor, the prescriber staying copied; not yet the médecin-chef")
    void plus15_theCover() {
        minutesAgo(16, 0);

        assertThat(service.escalateOverdue()).isEqualTo(1);

        told("dr.diallo", ESCALATION);
        told("dr.garde", ESCALATION);
        notTold("dr.chef");
        notTold("bio.kabore");
        notTold("admin.sawadogo");
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 1);
        verify(incidents, never()).save(any());
    }

    @Test
    @DisplayName("before +15 min nothing is sent again")
    void before15_nothing() {
        minutesAgo(14, 0);

        assertThat(service.escalateOverdue()).isZero();

        verify(notifications, never()).created(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("+30 min: the médecin-chef and the responsible biologist are added")
    void plus30_theMedicalLeadAndTheBiologist() {
        minutesAgo(31, 1);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(16));

        service.escalateOverdue();

        told("dr.chef", ESCALATION);
        told("bio.kabore", ESCALATION);
        told("dr.garde", ESCALATION);
        notTold("admin.sawadogo");
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 2);
    }

    @Test
    @DisplayName("+60 min: « résultat critique non résolu », an incident record, and a value-free flag for the administrators")
    void plus60_unresolvedAlertAndIncident() {
        minutesAgo(61, 2);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(31));

        service.escalateOverdue();

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(notifications).created(texts.capture(), eq("dr.chef"), eq(ESCALATION));
        assertThat(texts.getValue()).contains("NON RÉSOLU").contains("7,1").contains("Bea Ward");
        ArgumentCaptor<CriticalResultIncident> incident = ArgumentCaptor.forClass(CriticalResultIncident.class);
        verify(incidents).save(incident.capture());
        assertThat(incident.getValue().getLabResultId()).isEqualTo(result.getId());
        assertThat(incident.getValue().getHospitalId()).isEqualTo(hospital.getId());
        assertThat(incident.getValue().getCareSetting()).isEqualTo("INPATIENT");
        assertThat(incident.getValue().getMinutesUnacknowledged()).isBetween(60, 62);
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 3);

        // The administrator: no value, no test, no patient.
        ArgumentCaptor<String> flag = ArgumentCaptor.forClass(String.class);
        verify(notifications).created(flag.capture(), eq("admin.sawadogo"), eq(ESCALATION));
        assertThat(flag.getValue()).doesNotContain("7,1").doesNotContain("7.1").doesNotContain("Potassium")
            .doesNotContain("Bea").doesNotContain("Ward").contains("incident");
    }

    @Test
    @DisplayName("after the final alert the chain is reminded for as long as nobody acknowledges: level 12 and beyond")
    void theChainNeverGoesSilent() {
        minutesAgo(3 * 24 * 60, 12);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(24 * 60 + 1));
        when(incidents.findByLabResultIdAndResolvedAtIsNull(result.getId())).thenReturn(Optional.of(new CriticalResultIncident()));

        assertThat(service.escalateOverdue()).isEqualTo(1);

        told("dr.garde", ESCALATION);
        told("dr.chef", ESCALATION);
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 13);
        // A reminder: no second incident, no second flag to the administrators.
        verify(incidents, never()).save(any());
        notTold("admin.sawadogo");
    }

    @Test
    @DisplayName("a sweep that was down catches up one step a pass, in order: cover, then lead, then unresolved")
    void aLateSweepTakesOneStepAPass() {
        minutesAgo(75, 0);

        service.escalateOverdue();
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 1);
        told("dr.garde", ESCALATION);
        notTold("dr.chef");
        verify(incidents, never()).save(any());

        service.escalateOverdue();
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 2);
        told("dr.chef", ESCALATION);
        verify(incidents, never()).save(any());

        service.escalateOverdue();
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 3);
        verify(incidents).save(any());
    }

    @Test
    @DisplayName("a hospital's own minutes move the steps")
    void aHospitalsOwnMinutes() {
        hospital.setCriticalCoverAfterMinutes(5);
        hospital.setCriticalLeadAfterMinutes(10);
        hospital.setCriticalUnresolvedAfterMinutes(20);
        minutesAgo(11, 1);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(6));

        service.escalateOverdue();

        told("dr.chef", ESCALATION);
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 2);
        // And with the default minutes the same result would not have reached the cover yet.
        assertThat(CriticalResultEscalationLadder.due(0, LocalDateTime.now().minusMinutes(11), null,
            Minutes.DEFAULT, LocalDateTime.now())).isEmpty();
    }

    @Test
    @DisplayName("no administrator is ever on a value-bearing step, whatever the level")
    void noAdministratorOnAValueBearingStep() {
        for (int[] at : new int[][] {{16, 0}, {31, 1}, {600, 5}}) {
            clearInvocations(notifications);
            minutesAgo(at[0], at[1]);
            result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(at[1] >= 3 ? 121 : 31));
            service.escalateOverdue();
            notTold("admin.sawadogo");
        }
    }

    // ── The acknowledgement ─────────────────────────────────────────────

    @Test
    @DisplayName("only a matching read-back ends it, and records the name, the role, the time and the channel; it closes the incident")
    void theAcknowledgementRecordsWhoAsWhatWhenAndHow() {
        minutesAgo(90, 3);
        when(results.confirmReadBackIfUnconfirmed(eq(result.getId()), anyString(), any(), any(), any())).thenReturn(1);
        CriticalResultIncident open = CriticalResultIncident.builder().labResultId(result.getId()).build();
        when(incidents.findByLabResultIdAndResolvedAtIsNull(result.getId())).thenReturn(Optional.of(open));
        UUID clinician = UUID.randomUUID();

        service.recordReadBack(result, "7.1", clinician, "Dr Awa Garde", "ROLE_DOCTOR", "PHONE");

        assertThat(result.isAcknowledged()).isTrue();
        assertThat(result.getAcknowledgedByDisplay()).isEqualTo("Dr Awa Garde");
        assertThat(result.getAcknowledgedAt()).isNotNull();
        assertThat(result.getCriticalAckRole()).isEqualTo("ROLE_DOCTOR");
        assertThat(result.getCriticalAckChannel()).isEqualTo("PHONE");
        assertThat(open.getResolvedAt()).isNotNull();
        assertThat(open.getResolvedByUserId()).isEqualTo(clinician);
        assertThat(open.getResolvedByDisplay()).isEqualTo("Dr Awa Garde");
        assertThat(open.getResolution()).isEqualTo(CriticalResultIncident.RESOLVED_READ_BACK);
        verify(incidents).save(open);
    }

    @Test
    @DisplayName("the channel is the application when the clinician does not say")
    void theChannelDefaultsToTheApplication() {
        minutesAgo(5, 0);
        when(results.confirmReadBackIfUnconfirmed(eq(result.getId()), anyString(), any(), any(), any())).thenReturn(1);

        service.recordReadBack(result, "7.1", UUID.randomUUID(), "Inf. Garde", "ROLE_NURSE", null);

        assertThat(result.getCriticalAckChannel()).isEqualTo("IN_APP");
        assertThat(result.getCriticalAckRole()).isEqualTo("ROLE_NURSE");
    }

    @Test
    @DisplayName("an outpatient result follows the same ladder, and its incident says so")
    void anOutpatientResult() {
        result.getLabOrder().getEncounter().setEncounterType(EncounterType.CONSULTATION);
        when(recipients.nursesInCharge(hospital.getId(), false)).thenReturn(Set.of());
        minutesAgo(61, 2);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(31));

        service.escalateOverdue();

        ArgumentCaptor<CriticalResultIncident> incident = ArgumentCaptor.forClass(CriticalResultIncident.class);
        verify(incidents).save(incident.capture());
        assertThat(incident.getValue().getCareSetting()).isEqualTo("OUTPATIENT");
        // Nothing here ever texts a patient: the service's only SMS targets are staff accounts.
        verify(sms, never()).send(anyString(), anyString());
    }

    // ── The SMS of a step (a mocked gateway: nothing is sent from a test) ──────────

    private static User account(String username, String phone) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPhoneNumber(phone);
        return user;
    }

    @Test
    @DisplayName("with a gateway, a step texts each clinician it tells who has a number, once; a failed SMS stops neither the others nor the step")
    void aStepTextsEachClinicianWithANumberOnce() {
        when(sms.deliversRealSms()).thenReturn(true);
        when(users.findByUsernameInIgnoreCase(any())).thenReturn(List.of(account("dr.diallo", "+22670000001"),
            account("dr.garde", "+22670000002"), account("dr.sansnumero", null), account("dr.vide", " ")));
        doThrow(new IllegalStateException("gateway down")).when(sms).send(eq("+22670000001"), anyString());
        minutesAgo(16, 0);

        assertThat(service.escalateOverdue()).isEqualTo(1);

        ArgumentCaptor<List<String>> asked = ArgumentCaptor.captor();
        verify(users).findByUsernameInIgnoreCase(asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder("dr.diallo", "dr.garde");
        verify(sms).send(eq("+22670000001"), anyString());
        verify(sms).send(eq("+22670000002"), anyString());
        verify(sms, never()).send(isNull(), anyString());
        verify(sms, never()).send(eq(" "), anyString());
        // The notices and the stamp do not depend on the gateway.
        told("dr.diallo", ESCALATION);
        told("dr.garde", ESCALATION);
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("a reminder texts nobody, and no administrator is ever texted")
    void aReminderTextsNobody() {
        when(sms.deliversRealSms()).thenReturn(true);
        when(users.findByUsernameInIgnoreCase(any())).thenReturn(List.of(account("dr.diallo", "+22670000001"),
            account("admin.sawadogo", "+22670000009")));
        minutesAgo(3 * 24 * 60, 12);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(24 * 60 + 1));
        when(incidents.findByLabResultIdAndResolvedAtIsNull(result.getId())).thenReturn(Optional.of(new CriticalResultIncident()));

        assertThat(service.escalateOverdue()).isEqualTo(1);

        verify(sms, never()).send(anyString(), anyString());

        // The last step itself: the clinicians of the chain are asked for, never the administrators.
        clearInvocations(users, sms);
        minutesAgo(61, 2);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(31));
        when(incidents.findByLabResultIdAndResolvedAtIsNull(result.getId())).thenReturn(Optional.empty());

        assertThat(service.escalateOverdue()).isEqualTo(1);

        ArgumentCaptor<List<String>> asked = ArgumentCaptor.captor();
        verify(users).findByUsernameInIgnoreCase(asked.capture());
        assertThat(asked.getValue()).contains("dr.diallo", "dr.chef", "bio.kabore").doesNotContain("admin.sawadogo");
    }

    @Test
    @DisplayName("the incident is opened once: a step that finds it on record writes no second one")
    void theIncidentIsOpenedOnce() {
        when(incidents.findByLabResultIdAndResolvedAtIsNull(result.getId())).thenReturn(Optional.of(new CriticalResultIncident()));
        minutesAgo(61, 2);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(31));

        assertThat(service.escalateOverdue()).isEqualTo(1);

        verify(incidents, never()).save(any());
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("a result whose hospital cannot be resolved still tells its prescriber, and opens no incident")
    void aResultWithNoHospitalStillTellsThePrescriber() {
        result.getLabOrder().setHospital(null);
        minutesAgo(61, 2);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(31));

        assertThat(service.escalateOverdue()).isEqualTo(1);

        told("dr.diallo", ESCALATION);
        notTold("dr.garde");
        notTold("admin.sawadogo");
        verify(incidents, never()).save(any());
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 3);
    }

    // ── Review of #1291: after the commit, never over an acknowledgement, the incident's end, the backlog ──

    @AfterEach
    void noSynchronizationLeftBehind() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("review M1: at T0 with the prescriber away, the cover's SMS waits for the release to commit; a release that is rolled back texts nobody")
    void aRolledBackReleaseTextsNobody() {
        when(sms.deliversRealSms()).thenReturn(true);
        when(users.findByUsernameInIgnoreCase(any())).thenReturn(List.of(account("dr.garde", "+22670000002")));
        when(recipients.prescriberAbsent(prescriber)).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        service.notifyIfCritical(result);

        // The notices are written in the release's transaction; the gateway is not called inside it.
        told("dr.garde", ESCALATION);
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 1);
        verify(sms, never()).send(anyString(), anyString());

        // The release fails: a transaction that rolls back completes without its after-commit callbacks.
        for (TransactionSynchronization pending : TransactionSynchronizationManager.getSynchronizations()) {
            pending.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clearSynchronization();

        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("review M1: the sweep's SMS are sent once the sweep has committed, not while it holds its rows and its lock")
    void theSweepTextsAfterItsCommit() {
        when(sms.deliversRealSms()).thenReturn(true);
        when(users.findByUsernameInIgnoreCase(any())).thenReturn(List.of(account("dr.diallo", "+22670000001"),
            account("dr.garde", "+22670000002"), account("dr.double", "+22670000002")));
        minutesAgo(16, 0);
        TransactionSynchronizationManager.initSynchronization();

        assertThat(service.escalateOverdue()).isEqualTo(1);

        told("dr.garde", ESCALATION);
        verify(sms, never()).send(anyString(), anyString());

        for (TransactionSynchronization pending : TransactionSynchronizationManager.getSynchronizations()) {
            pending.afterCommit();
        }

        // One SMS a number, each number once.
        verify(sms).send(eq("+22670000001"), anyString());
        verify(sms).send(eq("+22670000002"), anyString());
    }

    @Test
    @DisplayName("review M2: a result acknowledged while the sweep ran is told to nobody, and the sweep never saves the row it loaded")
    void anAcknowledgementCommittedDuringTheSweepStands() {
        minutesAgo(16, 0);
        when(sms.deliversRealSms()).thenReturn(true);
        // The clinician's read-back committed after the sweep read the row: the conditional UPDATE finds nothing.
        when(results.stampEscalationIfUnacknowledged(eq(result.getId()), anyShort(), anyShort(), any())).thenReturn(0);

        assertThat(service.escalateOverdue()).isZero();

        verify(results).stampEscalationIfUnacknowledged(eq(result.getId()), eq((short) 0), eq((short) 1), any());
        verify(notifications, never()).created(anyString(), anyString(), anyString());
        verify(users, never()).findByUsernameInIgnoreCase(any());
        verify(incidents, never()).save(any());
        verify(results, never()).save(any());
        assertThat(result.getCriticalEscalationLevel()).isZero();
    }

    @Test
    @DisplayName("review M2: every step of the ladder is stamped on the row by the conditional UPDATE, level by level, and nothing is saved")
    void everyStepIsStampedByTheConditionalUpdate() {
        minutesAgo(200, 0);

        for (short level = 0; level < 3; level++) {
            assertThat(service.escalateOverdue()).isEqualTo(1);
            verify(results).stampEscalationIfUnacknowledged(eq(result.getId()), eq(level), eq((short) (level + 1)), any());
            result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(200));
        }

        verify(results, never()).save(any());
    }

    @Test
    @DisplayName("a step that could not be told is given back on the row: the next sweep takes it again")
    void aStepThatCouldNotBeToldIsGivenBack() {
        minutesAgo(16, 0);
        when(recipients.cover(hospital.getId(), "dr.diallo"))
            .thenThrow(new IllegalStateException("roster down")).thenReturn(Set.of("dr.garde"));

        assertThat(service.escalateOverdue()).isZero();

        assertThat(result.getCriticalEscalationLevel()).isZero();
        assertThat(result.getCriticalEscalatedAt()).isNull();
        notTold("dr.garde");

        assertThat(service.escalateOverdue()).isEqualTo(1);

        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 1);
        told("dr.garde", ESCALATION);
    }

    @Test
    @DisplayName("review L2: an incident closes with nobody's name on it when its result no longer grades as critical")
    void anIncidentClosesWhenTheResultIsNoLongerCritical() {
        result.setResultValue("4.2");
        result.setAbnormalFlag(AbnormalFlag.NORMAL);
        when(mapper.severityFlagOf(result)).thenReturn("NORMAL");
        minutesAgo(90, 3);
        CriticalResultIncident open = CriticalResultIncident.builder().labResultId(result.getId()).build();
        when(incidents.findByLabResultIdAndResolvedAtIsNull(result.getId())).thenReturn(Optional.of(open));

        assertThat(service.escalateOverdue()).isZero();

        assertThat(result.getCriticalNotifiedAt()).isNull();
        assertThat(result.getCriticalEscalationLevel()).isZero();
        assertThat(open.getResolution()).isEqualTo(CriticalResultIncident.RESOLVED_NO_LONGER_CRITICAL);
        assertThat(open.getResolvedAt()).isNotNull();
        assertThat(open.getResolvedByUserId()).isNull();
        assertThat(open.getResolvedByDisplay()).isNull();
        verify(incidents).save(open);
        verify(results, never()).save(any());
        verify(notifications, never()).created(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("review L2: an amended result closes its incident as amended; unanswered again at its new value, it opens another")
    void anAmendedResultClosesItsIncidentAndMayOpenAnother() {
        CriticalResultIncident aboutTheOldValue = CriticalResultIncident.builder().labResultId(result.getId()).build();
        when(incidents.findByLabResultIdAndResolvedAtIsNull(result.getId())).thenReturn(Optional.of(aboutTheOldValue));

        service.incidentEndedByAmendment(result);

        assertThat(aboutTheOldValue.getResolution()).isEqualTo(CriticalResultIncident.RESOLVED_AMENDED);
        assertThat(aboutTheOldValue.getResolvedAt()).isNotNull();
        assertThat(aboutTheOldValue.getResolvedByUserId()).isNull();
        verify(incidents).save(aboutTheOldValue);

        // The new value paged afresh and went unanswered in its turn: no open incident is on record.
        when(incidents.findByLabResultIdAndResolvedAtIsNull(result.getId())).thenReturn(Optional.empty());
        minutesAgo(61, 2);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(31));

        assertThat(service.escalateOverdue()).isEqualTo(1);

        ArgumentCaptor<CriticalResultIncident> saved = ArgumentCaptor.forClass(CriticalResultIncident.class);
        verify(incidents, times(2)).save(saved.capture());
        CriticalResultIncident aboutTheNewValue = saved.getAllValues().get(1);
        assertThat(aboutTheNewValue).isNotSameAs(aboutTheOldValue);
        assertThat(aboutTheNewValue.getResolvedAt()).isNull();
        assertThat(aboutTheNewValue.getResolution()).isNull();
    }

    @Test
    @DisplayName("an amendment of a result with no open incident closes nothing")
    void anAmendmentWithNoOpenIncidentClosesNothing() {
        service.incidentEndedByAmendment(result);

        verify(incidents, never()).save(any());
    }

    @Test
    @DisplayName("review L3: a result already waiting when the ladder was switched on keeps its notices and its incident, and is never texted about")
    void aBacklogResultIsNeverTextedAbout() {
        result.setCriticalBacklog(true);
        when(sms.deliversRealSms()).thenReturn(true);
        when(users.findByUsernameInIgnoreCase(any())).thenReturn(List.of(account("dr.garde", "+22670000002")));
        // Paged a month ago, left at the old chain's second round.
        minutesAgo(30 * 24 * 60, 1);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusDays(29));

        assertThat(service.escalateOverdue()).isEqualTo(1);
        result.setCriticalEscalatedAt(LocalDateTime.now().minusDays(29));
        assertThat(service.escalateOverdue()).isEqualTo(1);

        // Never dropped: the médecin-chef is told, then the incident opens.
        verify(notifications, times(2)).created(anyString(), eq("dr.chef"), eq(ESCALATION));
        verify(incidents).save(any());
        assertThat(result.getCriticalEscalationLevel()).isEqualTo((short) 3);
        // And no telephone rings about it.
        verify(users, never()).findByUsernameInIgnoreCase(any());
        verify(sms, never()).send(anyString(), anyString());
    }
}
