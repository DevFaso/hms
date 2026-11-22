package com.example.hms.service.vitals;

import com.example.hms.model.Admission;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.vitals.MaternalEarlyWarning.Score;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Policy 2026-11-20 (PROVISIONAL): who the maternal early warning applies to, and who each level
 * pages (the chart's UK grades mapped to the midwife in charge, the doctor and the senior doctor).
 */
class MaternalEarlyWarningServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 9, 0);
    private static final UUID HERE = UUID.randomUUID();

    private final MaternalEarlyWarningRules rules = new MaternalEarlyWarningRules();
    private final News2Applicability applicability = mock(News2Applicability.class);
    private final News2EscalationService news2 = mock(News2EscalationService.class);
    private final StaffRepository staff = mock(StaffRepository.class);
    private final OnCallScheduleRepository onCall = mock(OnCallScheduleRepository.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final MaternalEarlyWarningService service = new MaternalEarlyWarningService(rules, applicability, news2,
        staff, onCall, admissions, notifications, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    private Patient mother;

    @BeforeEach
    void wire() {
        mother = new Patient();
        mother.setId(UUID.randomUUID());
        mother.setFirstName("Awa");
        mother.setLastName("Sawadogo");
        mother.setDateOfBirth(LocalDate.of(1998, 3, 2));
        when(news2.recipients(any(), any(), any(), any())).thenAnswer(inv -> {
            Set<String> circle = new LinkedHashSet<>();
            if (inv.getArgument(2) != null) {
                circle.add(inv.getArgument(2));
            }
            circle.add("dr.attending");
            return circle;
        });
        when(staff.findActiveUsernamesByHospitalAndRole(HERE, "ROLE_MIDWIFE"))
            .thenReturn(List.of("sf.oncall", "sf.off"));
        when(staff.findActiveUsernamesByHospitalAndRole(HERE, "ROLE_DOCTOR"))
            .thenReturn(List.of("dr.oncall", "dr.attending", "dr.off"));
        when(onCall.findByHospitalAndWindow(eq(HERE), any(), any()))
            .thenReturn(List.of(shift("sf.oncall"), shift("dr.oncall")));
    }

    private static OnCallSchedule shift(String username) {
        User user = new User();
        user.setUsername(username);
        Staff member = new Staff();
        member.setUser(user);
        OnCallSchedule shift = new OnCallSchedule();
        shift.setStaff(member);
        return shift;
    }

    private PatientVitalSign.PatientVitalSignBuilder set() {
        Hospital hospital = Hospital.builder().build();
        hospital.setId(HERE);
        return PatientVitalSign.builder().patient(mother).hospital(hospital).recordedAt(NOW)
            .respiratoryRateBpm(16).spo2Percent(98).temperatureCelsius(36.8).heartRateBpm(80)
            .systolicBpMmHg(118).diastolicBpMmHg(74);
    }

    private void under(News2Applicability.Status status, LocalDateTime birthAt) {
        when(applicability.applicabilityOf(any(), any(), any()))
            .thenReturn(new News2Applicability.Applicability(status, birthAt));
    }

    private Set<String> paged() {
        ArgumentCaptor<String> who = ArgumentCaptor.forClass(String.class);
        verify(notifications, org.mockito.Mockito.atLeast(0)).createNotification(anyString(), any(), who.capture(),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        return new LinkedHashSet<>(who.getAllValues());
    }

    @Test
    @DisplayName("only a pregnant or postpartum woman gets a maternal score; an adult, a child and a newborn do not")
    void appliesOnlyUnderTheObstetricScope() {
        under(News2Applicability.Status.OBSTETRIC, null);
        assertThat(service.assess(mother, HERE, set().build())).isNotNull();
        for (News2Applicability.Status other : List.of(News2Applicability.Status.APPLIES,
                News2Applicability.Status.CHILD, News2Applicability.Status.NEWBORN)) {
            under(other, null);
            assertThat(service.assess(mother, HERE, set().build())).as(other.name()).isNull();
        }
    }

    @Test
    @DisplayName("switched off, nobody is scored and the obstetric record is not read")
    void switchedOffScoresNobody() {
        rules.setEnabled(false);
        assertThat(service.assess(mother, HERE, set().build())).isNull();
        verifyNoInteractions(applicability);
    }

    @Test
    @DisplayName("the pulse table follows the birth: 105 bpm scores 0 at 47 h and 1 at 48 h")
    void thePulseTableFollowsTheBirth() {
        under(News2Applicability.Status.OBSTETRIC, NOW.minusHours(47));
        assertThat(service.assess(mother, HERE, set().heartRateBpm(105).build()).total()).isZero();
        under(News2Applicability.Status.OBSTETRIC, NOW.minusHours(48));
        Score after = service.assess(mother, HERE, set().heartRateBpm(105).build());
        assertThat(after.total()).isEqualTo(1);
        assertThat(after.postBirthPulse()).isTrue();
    }

    @Test
    @DisplayName("low (0-1, no concern): nobody is paged")
    void lowPagesNobody() {
        under(News2Applicability.Status.OBSTETRIC, null);
        Score low = service.assess(mother, HERE, set().build());
        assertThat(service.escalate(set().build(), low, mother.getId(), HERE, "sf.recorder", null, Set.of())).isEmpty();
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("low-medium (2-4): the recorder, the responsible physician, the midwife in charge and the doctor on call")
    void lowMediumPagesTheMidwifeInChargeAndTheDoctor() {
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = set().heartRateBpm(125).build();
        Score score = service.assess(mother, HERE, vital);
        assertThat(score.responseTier()).isEqualTo(MaternalEarlyWarning.Tier.LOW_MEDIUM);

        Set<String> reached = service.escalate(vital, score, mother.getId(), HERE, "sf.recorder", null, Set.of());

        // On call first: the midwife and the doctor off the rota are not paged at this level.
        assertThat(reached).containsExactlyInAnyOrder("sf.recorder", "dr.attending", "sf.oncall", "dr.oncall");
        assertThat(paged()).isEqualTo(reached);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(notifications).createNotification(eq("mews.escalation.lowMedium"), args.capture(), eq("sf.oncall"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        // {0} the score, {1} the patient, {2} review within 30 min, {3} observations every 30 min.
        assertThat(args.getValue()[0]).isEqualTo(2);
        assertThat(args.getValue()[1]).isEqualTo("Awa Sawadogo");
        assertThat(args.getValue()[2]).isEqualTo(30);
        assertThat(args.getValue()[3]).isEqualTo(30L);
    }

    @Test
    @DisplayName("nobody on the rota: every midwife and every doctor of the hospital is paged")
    void withNobodyOnCallTheWholeGradeIsPaged() {
        when(onCall.findByHospitalAndWindow(eq(HERE), any(), any())).thenReturn(List.of());
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = set().heartRateBpm(125).build();

        Set<String> reached = service.escalate(vital, service.assess(mother, HERE, vital), mother.getId(), HERE,
            "sf.recorder", null, Set.of());

        assertThat(reached).containsExactlyInAnyOrder("sf.recorder", "dr.attending", "sf.oncall", "sf.off",
            "dr.oncall", "dr.off");
    }

    @Test
    @DisplayName("medium (5-7): the senior doctor too (the head of her department and the doctors on call), within 15 min")
    void mediumAddsTheSeniorDoctor() {
        User headUser = new User();
        headUser.setUsername("dr.head");
        Staff head = new Staff();
        head.setUser(headUser);
        Department maternity = new Department();
        maternity.setHeadOfDepartment(head);
        Admission stay = new Admission();
        stay.setDepartment(maternity);
        stay.setAdmissionDateTime(NOW.minusDays(1));
        when(admissions.findByPatient_IdAndHospital_IdAndStatusIn(eq(mother.getId()), eq(HERE), any()))
            .thenReturn(List.of(stay));
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = set().heartRateBpm(125).respiratoryRateBpm(26).temperatureCelsius(37.4).build();
        Score score = service.assess(mother, HERE, vital);
        assertThat(score.responseTier()).isEqualTo(MaternalEarlyWarning.Tier.MEDIUM);

        Set<String> reached = service.escalate(vital, score, mother.getId(), HERE, "sf.recorder", null, Set.of());

        // Review of #1241: with a rota, the doctor who is not on call is not paged.
        assertThat(reached).containsExactlyInAnyOrder("sf.recorder", "dr.attending", "sf.oncall", "dr.oncall",
            "dr.head");
        verify(notifications).createNotification(eq("mews.escalation.medium"), any(), eq("dr.head"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
    }

    @Test
    @DisplayName("high (8 or more) pages the same circle with the immediate wording")
    void highIsImmediate() {
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = set().heartRateBpm(125).respiratoryRateBpm(26).temperatureCelsius(38.0)
            .spo2Percent(90).build();
        Score score = service.assess(mother, HERE, vital);
        assertThat(score.responseTier()).isEqualTo(MaternalEarlyWarning.Tier.HIGH);

        service.escalate(vital, score, mother.getId(), HERE, "sf.recorder", null, Set.of());

        verify(notifications).createNotification(eq("mews.escalation.high"), any(), eq("dr.oncall"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        verify(notifications, never()).createNotification(anyString(), any(), eq("dr.off"), anyString());
    }

    @Test
    @DisplayName("a RED add-on alert pages the high circle whatever the score, and names the alert")
    void aRedAlertPagesTheHighCircle() {
        under(News2Applicability.Status.OBSTETRIC, null);
        // Pulse 150 over 85: shock index 1.76 (red). The score itself is 4, low-medium.
        PatientVitalSign vital = set().heartRateBpm(150).systolicBpMmHg(85).build();
        Score score = service.assess(mother, HERE, vital);
        assertThat(score.total()).isEqualTo(4);
        assertThat(score.responseTier()).isEqualTo(MaternalEarlyWarning.Tier.HIGH);

        Set<String> reached = service.escalate(vital, score, mother.getId(), HERE, "sf.recorder", null, Set.of());

        assertThat(reached).contains("dr.oncall").doesNotContain("dr.off");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(notifications).createNotification(eq("mews.escalation.red"), args.capture(), eq("dr.oncall"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        assertThat(String.valueOf(args.getValue()[4])).contains("mews.alert.SEVERE_SHOCK");
    }

    @Test
    @DisplayName("one reading never pages a person twice: whoever another notice reached is skipped, whatever the case")
    void anAlreadyPagedPersonIsSkipped() {
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = set().heartRateBpm(125).build();

        Set<String> reached = service.escalate(vital, service.assess(mother, HERE, vital), mother.getId(), HERE,
            "sf.recorder", null, Set.of("SF.Recorder", "dr.attending"));

        assertThat(reached).containsExactlyInAnyOrder("sf.oncall", "dr.oncall");
        verify(notifications, never()).createNotification(anyString(), any(), eq("sf.recorder"), anyString());
        verify(notifications, never()).createNotification(anyString(), any(), eq("dr.attending"), anyString());
    }

    @Test
    @DisplayName("a notice that cannot be created never fails the vital set, and the others still go")
    void aFailedNoticeDoesNotStopTheOthers() {
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = set().heartRateBpm(125).build();
        doThrow(new IllegalStateException("down")).when(notifications)
            .createNotification(anyString(), any(), eq("sf.oncall"), anyString());

        Set<String> reached = service.escalate(vital, service.assess(mother, HERE, vital), mother.getId(), HERE,
            "sf.recorder", null, Set.of());

        assertThat(reached).containsExactlyInAnyOrder("sf.recorder", "dr.attending", "dr.oncall");
    }

    @Test
    @DisplayName("the chart reads the score, its level, what is missing, the concerns and the alerts")
    void describesTheScoreForTheChart() {
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign partograph = PatientVitalSign.builder().patient(mother).recordedAt(NOW).heartRateBpm(125)
            .systolicBpMmHg(118).diastolicBpMmHg(74).temperatureCelsius(36.8).maternalConcerns("VAGINAL_BLEEDING")
            .build();
        PatientVitalSignResponseDTO dto = new PatientVitalSignResponseDTO();

        service.describe(service.assess(mother, HERE, partograph), dto);

        assertThat(dto.getMewsScore()).isEqualTo(2);
        assertThat(dto.getMewsTier()).isEqualTo("LOW_MEDIUM");
        assertThat(dto.getMewsComplete()).isFalse();
        assertThat(dto.getMewsMissing()).containsExactly("RESPIRATORY_RATE", "SPO2");
        assertThat(dto.getMewsConcerns()).containsExactly("VAGINAL_BLEEDING");
        assertThat(dto.getMewsAlerts()).containsExactly("SHOCK");
        assertThat(dto.getMewsReviewWithinMinutes()).isEqualTo(30);

        // A weight alone has nothing to score: no chip.
        PatientVitalSignResponseDTO weightOnly = new PatientVitalSignResponseDTO();
        service.describe(service.assess(mother, HERE,
            PatientVitalSign.builder().patient(mother).recordedAt(NOW).weightKg(61.0).build()), weightOnly);
        assertThat(weightOnly.getMewsTier()).isNull();
    }

    @Test
    @DisplayName("a write accounts a raised warning that rests on another hospital's record; a low score or a read does not")
    void aRaisedWarningOnAForeignRecordIsAccounted() {
        UUID elsewhere = UUID.randomUUID();
        News2Applicability.Applicability foreign =
            new News2Applicability.Applicability(News2Applicability.Status.OBSTETRIC, null, elsewhere);
        when(applicability.applicabilityOf(any(), any(), any())).thenReturn(foreign);

        // A calm set raises nothing: nothing to account.
        service.assess(mother, HERE, set().build(), true);
        // A read (the chart, the advisory) never accounts here: those reads account their own rows.
        service.assess(mother, HERE, set().heartRateBpm(125).build());
        // The pressure warning of the same reading already accounted it.
        service.assess(mother, HERE, set().heartRateBpm(125).build(), false);
        verify(applicability, never()).accountForeignRecord(any(), any(), any());

        service.assess(mother, HERE, set().heartRateBpm(125).build(), true);
        verify(applicability).accountForeignRecord(mother.getId(), HERE, foreign);
    }

    // ---- Review of #1241 (eb0e8566): the rota at medium and high, and the page cool-down ----

    @Test
    @DisplayName("medium with nobody on the rota: every doctor of the hospital is paged (the fallback only)")
    void review1241_withNoRotaMediumPagesEveryDoctor() {
        when(onCall.findByHospitalAndWindow(eq(HERE), any(), any())).thenReturn(List.of());
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = set().heartRateBpm(125).respiratoryRateBpm(26).temperatureCelsius(37.4).build();

        Set<String> reached = service.escalate(vital, service.assess(mother, HERE, vital), mother.getId(), HERE,
            "sf.recorder", null, Set.of());

        assertThat(reached).containsExactlyInAnyOrder("sf.recorder", "dr.attending", "sf.oncall", "sf.off",
            "dr.oncall", "dr.off");
    }

    private com.example.hms.repository.PatientVitalSignRepository withTheCoolDown() {
        com.example.hms.repository.PatientVitalSignRepository vitals =
            mock(com.example.hms.repository.PatientVitalSignRepository.class);
        service.setVitalSignRepository(vitals);
        return vitals;
    }

    /**
     * A page of {@code tier} sent {@code minutesAgo} minutes ago is on record for her: the repository
     * answers as the query does (one of the levels asked for, paged at or after {@code since}).
     */
    private void pagedBefore(com.example.hms.repository.PatientVitalSignRepository vitals, String tier,
                             int minutesAgo) {
        // doAnswer: stubbing again (a test moves the page in time) must not run the earlier answer.
        doAnswer(inv -> {
            java.util.Collection<String> levels = inv.getArgument(2);
            LocalDateTime since = inv.getArgument(3);
            return levels.contains(tier) && !NOW.minusMinutes(minutesAgo).isBefore(since);
        }).when(vitals).existsByPatient_IdAndHospital_IdAndMewsPagedTierInAndMewsPagedAtGreaterThanEqual(
            eq(mother.getId()), eq(HERE), any(), any());
    }

    /** A complete set at the level asked for: low-medium (2), medium (5) or high (8). */
    private PatientVitalSign setAt(MaternalEarlyWarning.Tier tier) {
        PatientVitalSign.PatientVitalSignBuilder set = set().heartRateBpm(125);
        if (tier != MaternalEarlyWarning.Tier.LOW_MEDIUM) {
            set.respiratoryRateBpm(26).temperatureCelsius(37.4);
        }
        if (tier == MaternalEarlyWarning.Tier.HIGH) {
            set.temperatureCelsius(38.0).spo2Percent(90);
        }
        return set.build();
    }

    private Set<String> page(PatientVitalSign vital, MaternalEarlyWarning.Tier expected) {
        Score score = service.assess(mother, HERE, vital);
        assertThat(score.responseTier()).isEqualTo(expected);
        return service.escalate(vital, score, mother.getId(), HERE, "sf.recorder", null, Set.of());
    }

    @Test
    @DisplayName("cool-down decision: low-medium 10 min after a high page pages nobody; so does medium; the score is still computed")
    void decision_aRecentHigherPageSilencesALowerLevel() {
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        pagedBefore(vitals, "HIGH", 10);
        under(News2Applicability.Status.OBSTETRIC, null);

        PatientVitalSign lowMedium = setAt(MaternalEarlyWarning.Tier.LOW_MEDIUM);
        assertThat(page(lowMedium, MaternalEarlyWarning.Tier.LOW_MEDIUM)).isEmpty();
        PatientVitalSign medium = setAt(MaternalEarlyWarning.Tier.MEDIUM);
        assertThat(page(medium, MaternalEarlyWarning.Tier.MEDIUM)).isEmpty();

        verifyNoInteractions(notifications);
        // Only the page is withheld: neither reading is marked as having paged, and the score stands.
        assertThat(lowMedium.getMewsPagedTier()).isNull();
        assertThat(medium.getMewsPagedTier()).isNull();
        assertThat(service.assess(mother, HERE, lowMedium).total()).isEqualTo(2);
        // The question asked for a low-medium page is: this level or any above it, in the last 30 minutes.
        verify(vitals).existsByPatient_IdAndHospital_IdAndMewsPagedTierInAndMewsPagedAtGreaterThanEqual(
            mother.getId(), HERE, List.of("LOW_MEDIUM", "MEDIUM", "HIGH"), NOW.minusMinutes(30));
    }

    @Test
    @DisplayName("cool-down decision: high 5 min after a medium page goes through at once")
    void decision_aHigherLevelGoesThroughAfterALowerPage() {
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        pagedBefore(vitals, "MEDIUM", 5);
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign high = setAt(MaternalEarlyWarning.Tier.HIGH);

        Set<String> reached = page(high, MaternalEarlyWarning.Tier.HIGH);

        assertThat(reached).contains("sf.recorder", "sf.oncall", "dr.oncall");
        assertThat(high.getMewsPagedTier()).isEqualTo("HIGH");
        // Nothing is above high: only a recent HIGH page could have held it back.
        verify(vitals).existsByPatient_IdAndHospital_IdAndMewsPagedTierInAndMewsPagedAtGreaterThanEqual(
            mother.getId(), HERE, List.of("HIGH"), NOW.minusMinutes(30));
        // The medium page of 5 minutes ago does silence a medium or a low-medium reading.
        assertThat(page(setAt(MaternalEarlyWarning.Tier.MEDIUM), MaternalEarlyWarning.Tier.MEDIUM)).isEmpty();
        assertThat(page(setAt(MaternalEarlyWarning.Tier.LOW_MEDIUM), MaternalEarlyWarning.Tier.LOW_MEDIUM)).isEmpty();
    }

    @Test
    @DisplayName("cool-down decision: past the 30 minutes the clock has run out, for the same level and for a lower one")
    void decision_theWindowEnds() {
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        under(News2Applicability.Status.OBSTETRIC, null);
        // Exactly 30 minutes ago is still inside the window; 31 is not.
        pagedBefore(vitals, "HIGH", 30);
        assertThat(page(setAt(MaternalEarlyWarning.Tier.LOW_MEDIUM), MaternalEarlyWarning.Tier.LOW_MEDIUM)).isEmpty();
        pagedBefore(vitals, "HIGH", 31);
        assertThat(page(setAt(MaternalEarlyWarning.Tier.LOW_MEDIUM), MaternalEarlyWarning.Tier.LOW_MEDIUM)).isNotEmpty();
        assertThat(page(setAt(MaternalEarlyWarning.Tier.HIGH), MaternalEarlyWarning.Tier.HIGH)).isNotEmpty();
    }

    @Test
    @DisplayName("cool-down: paged at this level within 30 min, the same level pages nobody and the reading is not marked")
    void review1241_theSameLevelIsNotPagedAgainWithinTheCoolDown() {
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        pagedBefore(vitals, "LOW_MEDIUM", 10);
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = set().heartRateBpm(125).build();

        Set<String> reached = service.escalate(vital, service.assess(mother, HERE, vital), mother.getId(), HERE,
            "sf.recorder", null, Set.of());

        assertThat(reached).isEmpty();
        verifyNoInteractions(notifications);
        assertThat(vital.getMewsPagedTier()).isNull();
        assertThat(vital.getMewsPagedAt()).isNull();
    }

    @Test
    @DisplayName("cool-down: a page marks the reading with its level and the time; that is what the next reading reads")
    void review1241_aPageMarksTheReading() {
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = set().heartRateBpm(125).build();

        Set<String> reached = service.escalate(vital, service.assess(mother, HERE, vital), mother.getId(), HERE,
            "sf.recorder", null, Set.of());

        assertThat(reached).isNotEmpty();
        assertThat(vital.getMewsPagedTier()).isEqualTo("LOW_MEDIUM");
        assertThat(vital.getMewsPagedAt()).isEqualTo(NOW);
        // The window is the rule set's 30 minutes, at this hospital, for this woman, this level and above.
        verify(vitals).existsByPatient_IdAndHospital_IdAndMewsPagedTierInAndMewsPagedAtGreaterThanEqual(
            mother.getId(), HERE, List.of("LOW_MEDIUM", "MEDIUM", "HIGH"), NOW.minusMinutes(30));
    }

    @Test
    @DisplayName("cool-down: a higher level goes through at once, whatever was paged at a lower one")
    void review1241_aHigherLevelAlwaysGoesThrough() {
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        // She was paged at low-medium a few minutes ago; never at high.
        pagedBefore(vitals, "LOW_MEDIUM", 3);
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = set().heartRateBpm(125).respiratoryRateBpm(26).temperatureCelsius(38.0)
            .spo2Percent(90).build();
        Score score = service.assess(mother, HERE, vital);
        assertThat(score.responseTier()).isEqualTo(MaternalEarlyWarning.Tier.HIGH);

        Set<String> reached = service.escalate(vital, score, mother.getId(), HERE, "sf.recorder", null, Set.of());

        assertThat(reached).contains("sf.oncall", "dr.oncall");
        assertThat(vital.getMewsPagedTier()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("cool-down: the pressure notice that carried the score counts as the page; a low score and a 0 window never read the table")
    void review1241_whatCountsAsAPageAndWhenTheTableIsRead() {
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        under(News2Applicability.Status.OBSTETRIC, null);
        // Everyone the level would page was already told by the pressure notice of this reading.
        PatientVitalSign told = set().heartRateBpm(125).build();
        Set<String> reached = service.escalate(told, service.assess(mother, HERE, told), mother.getId(), HERE,
            "sf.recorder", null, Set.of("sf.recorder", "dr.attending", "sf.oncall", "dr.oncall"));
        assertThat(reached).isEmpty();
        assertThat(told.getMewsPagedTier()).isEqualTo("LOW_MEDIUM");

        clearInvocations(vitals);
        // A low score pages nobody: nothing to hold back, nothing read.
        PatientVitalSign calm = set().build();
        service.escalate(calm, service.assess(mother, HERE, calm), mother.getId(), HERE, "sf.recorder", null, Set.of());
        assertThat(calm.getMewsPagedTier()).isNull();
        // Switched off (0), every reading pages as before.
        rules.getEscalation().setPageCoolDownMinutes(0);
        PatientVitalSign again = set().heartRateBpm(125).build();
        assertThat(service.escalate(again, service.assess(mother, HERE, again), mother.getId(), HERE, "sf.recorder",
            null, Set.of())).isNotEmpty();
        verifyNoInteractions(vitals);
    }

    // ---- Review of #1241, round 2: a RED alert is never withheld by the cool-down ----

    /** Pulse 160 over 85/50: shock index 1.88, a RED severe shock; the score itself is 6. */
    private PatientVitalSign severeShock() {
        return set().heartRateBpm(160).systolicBpMmHg(85).diastolicBpMmHg(50).build();
    }

    @Test
    @DisplayName("round 2: a high page 10 min ago does not silence a severe shock; the cool-down is not even asked")
    void round2_aRedAlertIsNeverWithheld() {
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        pagedBefore(vitals, "HIGH", 10);
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign shock = severeShock();
        Score score = service.assess(mother, HERE, shock);
        assertThat(score.total()).isEqualTo(6);
        assertThat(score.alerts()).containsExactly(MaternalEarlyWarning.HardAlert.SEVERE_SHOCK);
        assertThat(score.responseTier()).isEqualTo(MaternalEarlyWarning.Tier.HIGH);

        MaternalEarlyWarningService.Page page =
            service.page(shock, score, mother.getId(), HERE, "sf.recorder", null, Set.of());

        assertThat(page.status()).isEqualTo(MaternalEarlyWarningService.PageStatus.SENT);
        assertThat(page.paged()).contains("sf.recorder", "sf.oncall", "dr.oncall");
        verify(notifications).createNotification(eq("mews.escalation.red"), any(), eq("dr.oncall"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        assertThat(shock.getMewsPagedTier()).isEqualTo("HIGH");
        verifyNoInteractions(vitals);

        // The same high level WITHOUT a red alert is withheld by that same page of 10 minutes ago.
        PatientVitalSign high = setAt(MaternalEarlyWarning.Tier.HIGH);
        Score plain = service.assess(mother, HERE, high);
        assertThat(plain.red()).isFalse();
        MaternalEarlyWarningService.Page withheld =
            service.page(high, plain, mother.getId(), HERE, "sf.recorder", null, Set.of());
        assertThat(withheld.status()).isEqualTo(MaternalEarlyWarningService.PageStatus.WITHHELD);
        assertThat(withheld.paged()).isEmpty();
        assertThat(high.getMewsPagedTier()).isNull();
    }

    @Test
    @DisplayName("round 2: a red pressure paged at 10:00 does not silence a severe shock at 10:20")
    void round2_aRedPageDoesNotSilenceTheNextRedAlert() {
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        under(News2Applicability.Status.OBSTETRIC, null);
        // 10:00, 164/114: a RED severe pressure. It pages and marks the reading at the high level.
        PatientVitalSign pressure = set().systolicBpMmHg(164).diastolicBpMmHg(114).build();
        Score first = service.assess(mother, HERE, pressure);
        assertThat(first.alerts()).containsExactly(MaternalEarlyWarning.HardAlert.SEVERE_HYPERTENSION);
        assertThat(service.page(pressure, first, mother.getId(), HERE, "sf.recorder", null, Set.of()).status())
            .isEqualTo(MaternalEarlyWarningService.PageStatus.SENT);
        assertThat(pressure.getMewsPagedTier()).isEqualTo("HIGH");

        // 10:20: that page is on record, 20 minutes old. The severe shock still pages.
        pagedBefore(vitals, "HIGH", 20);
        clearInvocations(notifications);
        PatientVitalSign shock = severeShock();
        MaternalEarlyWarningService.Page page = service.page(shock, service.assess(mother, HERE, shock),
            mother.getId(), HERE, "sf.recorder", null, Set.of());

        assertThat(page.status()).isEqualTo(MaternalEarlyWarningService.PageStatus.SENT);
        verify(notifications).createNotification(eq("mews.escalation.red"), any(), eq("sf.oncall"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
    }

    @Test
    @DisplayName("round 2: the page says what became of it: not due, sent, withheld, or nobody reached")
    void round2_thePageSaysWhatBecameOfIt() {
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign calm = set().build();
        assertThat(service.page(calm, service.assess(mother, HERE, calm), mother.getId(), HERE, "sf.recorder", null,
            Set.of()).status()).isEqualTo(MaternalEarlyWarningService.PageStatus.NOT_DUE);

        PatientVitalSign lowMedium = setAt(MaternalEarlyWarning.Tier.LOW_MEDIUM);
        assertThat(service.page(lowMedium, service.assess(mother, HERE, lowMedium), mother.getId(), HERE,
            "sf.recorder", null, Set.of()).status()).isEqualTo(MaternalEarlyWarningService.PageStatus.SENT);

        pagedBefore(vitals, "LOW_MEDIUM", 5);
        PatientVitalSign again = setAt(MaternalEarlyWarning.Tier.LOW_MEDIUM);
        assertThat(service.page(again, service.assess(mother, HERE, again), mother.getId(), HERE, "sf.recorder", null,
            Set.of()).status()).isEqualTo(MaternalEarlyWarningService.PageStatus.WITHHELD);

        // Nobody to tell (no recorder, nobody holds a role, every notice fails): the page is not called sent.
        pagedBefore(vitals, "LOW_MEDIUM", 45);
        doThrow(new IllegalStateException("down")).when(notifications)
            .createNotification(anyString(), any(), anyString(), anyString());
        PatientVitalSign unreachable = setAt(MaternalEarlyWarning.Tier.LOW_MEDIUM);
        MaternalEarlyWarningService.Page nobody = service.page(unreachable, service.assess(mother, HERE, unreachable),
            mother.getId(), HERE, "sf.recorder", null, Set.of());
        assertThat(nobody.status()).isEqualTo(MaternalEarlyWarningService.PageStatus.NOBODY);
        assertThat(unreachable.getMewsPagedTier()).isNull();
    }

    // ---- QA D3304 (r70): a medium, high or red page is also an urgent alert to acknowledge ----

    private com.example.hms.service.maternity.ObstetricAlertEscalationService withTheInBasket() {
        com.example.hms.service.maternity.ObstetricAlertEscalationService alerts =
            mock(com.example.hms.service.maternity.ObstetricAlertEscalationService.class);
        service.setObstetricAlerts(alerts);
        service.setLocalizer(new com.example.hms.service.i18n.NotificationLocalizer(
            com.example.hms.i18n.TestMessageSources.bundles()));
        return alerts;
    }

    private PatientVitalSign saved(PatientVitalSign vital, String recorderUsername, UUID recorderUserId) {
        vital.setId(UUID.randomUUID());
        User recorder = new User();
        recorder.setId(recorderUserId);
        recorder.setUsername(recorderUsername);
        Staff nurse = new Staff();
        nurse.setUser(recorder);
        vital.setRecordedByStaff(nurse);
        return vital;
    }

    private com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert filed(
            com.example.hms.service.maternity.ObstetricAlertEscalationService alerts) {
        ArgumentCaptor<com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert> alert =
            ArgumentCaptor.forClass(com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert.class);
        verify(alerts).raiseAfterCommit(alert.capture());
        return alert.getValue();
    }

    @Test
    @DisplayName("D3304: a medium page (MEWS 5) from the ward vitals files an urgent alert to acknowledge, in the reader's words")
    void d3304_aMediumPageIsFiledForAcknowledgement() {
        com.example.hms.service.maternity.ObstetricAlertEscalationService alerts = withTheInBasket();
        under(News2Applicability.Status.OBSTETRIC, null);
        UUID recorderId = UUID.randomUUID();
        PatientVitalSign vital = saved(setAt(MaternalEarlyWarning.Tier.MEDIUM), "sf.recorder", recorderId);
        Score score = service.assess(mother, HERE, vital);

        service.page(vital, score, mother.getId(), HERE, "sf.recorder", null, Set.of());

        com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert alert = filed(alerts);
        assertThat(alert.sourceType()).isEqualTo("VITAL_SIGN");
        assertThat(alert.sourceId()).isEqualTo(vital.getId());
        assertThat(alert.hospitalId()).isEqualTo(HERE);
        assertThat(alert.patientId()).isEqualTo(mother.getId());
        assertThat(alert.alertType()).isEqualTo(MaternalEarlyWarningService.ALERT_TYPE_MEDIUM);
        // The recorder cannot acknowledge her own alert: she is the documenter.
        assertThat(alert.documentedByUserId()).isEqualTo(recorderId);
        assertThat(alert.alertMessageKey()).isEqualTo("maternalWarning.alert.msg.MEWS_MEDIUM");
        assertThat(alert.alertMessageArgs().get(0)).isEqualTo(score.total());
        // Staff-locale (French) title and sentence, with the provisional marker.
        assertThat(alert.notificationText()).startsWith("Alerte maternelle (MEWS, préoccupation moyenne) pour Awa Sawadogo")
            .contains("MEWS " + score.total()).contains("15 min").contains("provisoire");
    }

    @Test
    @DisplayName("D3304: the high level and a RED alert are filed too; the low-medium notice is not an urgent alert")
    void d3304_highAndRedAreFiledAndLowMediumIsNot() {
        com.example.hms.service.maternity.ObstetricAlertEscalationService alerts = withTheInBasket();
        under(News2Applicability.Status.OBSTETRIC, null);

        PatientVitalSign lowMedium = saved(setAt(MaternalEarlyWarning.Tier.LOW_MEDIUM), "sf.recorder", UUID.randomUUID());
        assertThat(service.page(lowMedium, service.assess(mother, HERE, lowMedium), mother.getId(), HERE, "sf.recorder",
            null, Set.of()).status()).isEqualTo(MaternalEarlyWarningService.PageStatus.SENT);
        verifyNoInteractions(alerts);

        PatientVitalSign high = saved(setAt(MaternalEarlyWarning.Tier.HIGH), "sf.recorder", UUID.randomUUID());
        service.page(high, service.assess(mother, HERE, high), mother.getId(), HERE, "sf.recorder", null, Set.of());
        assertThat(filed(alerts).alertType()).isEqualTo(MaternalEarlyWarningService.ALERT_TYPE_HIGH);

        clearInvocations(alerts);
        PatientVitalSign shock = saved(severeShock(), "sf.recorder", UUID.randomUUID());
        service.page(shock, service.assess(mother, HERE, shock), mother.getId(), HERE, "sf.recorder", null, Set.of());
        com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert red = filed(alerts);
        assertThat(red.alertType()).isEqualTo(MaternalEarlyWarningService.ALERT_TYPE_RED);
        assertThat(red.notificationText()).contains("alerte rouge").contains("indice de choc en zone rouge");
    }

    @Test
    @DisplayName("D3304: the cool-down holds the item with the page, and a RED alert is never held")
    void d3304_theItemFollowsThePage() {
        com.example.hms.service.maternity.ObstetricAlertEscalationService alerts = withTheInBasket();
        com.example.hms.repository.PatientVitalSignRepository vitals = withTheCoolDown();
        pagedBefore(vitals, "MEDIUM", 2);
        under(News2Applicability.Status.OBSTETRIC, null);

        // MEWS 6 two minutes after the MEWS 5 page: withheld, and nothing more to acknowledge.
        PatientVitalSign again = saved(setAt(MaternalEarlyWarning.Tier.MEDIUM), "sf.recorder", UUID.randomUUID());
        assertThat(service.page(again, service.assess(mother, HERE, again), mother.getId(), HERE, "sf.recorder", null,
            Set.of()).status()).isEqualTo(MaternalEarlyWarningService.PageStatus.WITHHELD);
        verifyNoInteractions(alerts);

        // A severe shock in the same half hour: paged at once, and filed at once.
        PatientVitalSign shock = saved(severeShock(), "sf.recorder", UUID.randomUUID());
        assertThat(service.page(shock, service.assess(mother, HERE, shock), mother.getId(), HERE, "sf.recorder", null,
            Set.of()).status()).isEqualTo(MaternalEarlyWarningService.PageStatus.SENT);
        assertThat(filed(alerts).alertType()).isEqualTo(MaternalEarlyWarningService.ALERT_TYPE_RED);
    }

    @Test
    @DisplayName("D3304: a filing that fails never fails the vital set, and the page still goes out")
    void d3304_aFailedFilingNeverFailsThePage() {
        com.example.hms.service.maternity.ObstetricAlertEscalationService alerts = withTheInBasket();
        org.mockito.Mockito.doThrow(new IllegalStateException("down")).when(alerts).raiseAfterCommit(any());
        under(News2Applicability.Status.OBSTETRIC, null);
        PatientVitalSign vital = saved(setAt(MaternalEarlyWarning.Tier.MEDIUM), "sf.recorder", UUID.randomUUID());

        assertThat(service.page(vital, service.assess(mother, HERE, vital), mother.getId(), HERE, "sf.recorder", null,
            Set.of()).status()).isEqualTo(MaternalEarlyWarningService.PageStatus.SENT);
    }
}
