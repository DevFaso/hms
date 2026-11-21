package com.example.hms.service.vitals;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.payload.dto.NewbornVitalAlertDTO;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.maternity.NewbornAlertRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA D1564 (round 43, nurse-triage-inpatient n1/n9): a 4-day-old's HR 80, 35.0 °C and 37.8 °C
 * saved as not significant, judged with adult limits. The bands are NewbornAlertRules',
 * PROVISIONAL pending the neonatal lead's approval.
 */
class VitalSignJudgementTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    private final NewbornAssessmentRepository assessments = mock(NewbornAssessmentRepository.class);
    private final News2EscalationService news2 = mock(News2EscalationService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final com.example.hms.repository.DeliveryRecordRepository deliveries =
        mock(com.example.hms.repository.DeliveryRecordRepository.class);
    private final com.example.hms.repository.DeliveryNewbornRepository deliveryNewborns =
        mock(com.example.hms.repository.DeliveryNewbornRepository.class);
    private final VitalSignJudgement judgement = new VitalSignJudgement(assessments, news2, notifications,
        com.example.hms.i18n.TestMessageSources.bundles(), deliveryNewborns,
        com.example.hms.service.vitals.SevereHypertensionFixtures.actingHospitalOnly(deliveries));

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static Patient bornDaysAgo(int days) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Bebe");
        patient.setLastName("Ouedraogo");
        patient.setDateOfBirth(NOW.toLocalDate().minusDays(days));
        return patient;
    }

    private static PatientVitalSign.PatientVitalSignBuilder set(Patient patient) {
        return PatientVitalSign.builder().patient(patient).recordedAt(NOW);
    }

    /** The hospital a blood-pressure set is taken at (the rule reads obstetric records there). */
    private static final UUID HERE = UUID.randomUUID();

    private static PatientVitalSign.PatientVitalSignBuilder bpSet(Patient patient) {
        Hospital hospital = Hospital.builder().build();
        hospital.setId(HERE);
        return set(patient).hospital(hospital);
    }

    private static List<String> codes(VitalSignJudgement.Judgement j) {
        return j.newbornFindings().stream().map(NewbornAlertRules.Finding::code).toList();
    }

    @Test
    @DisplayName("D1564: HR 80, 35.0 °C and 37.8 °C on a 4-day-old are significant (adult limits passed them)")
    void theRound43ReadingsAreSignificantForANewborn() {
        Patient baby = bornDaysAgo(4);
        for (PatientVitalSign vital : List.of(set(baby).heartRateBpm(80).build(),
                set(baby).temperatureCelsius(35.0).build(), set(baby).temperatureCelsius(37.8).build())) {
            VitalSignJudgement.Judgement j = judgement.judge(vital);
            assertThat(j.newborn()).isTrue();
            assertThat(vital.isClinicallySignificant()).as(vital.toString()).isTrue();
        }
        assertThat(codes(judgement.judge(set(baby).heartRateBpm(80).build()))).contains("newborn-heart-rate");
        assertThat(codes(judgement.judge(set(baby).temperatureCelsius(35.0).build())))
            .contains("newborn-temperature-critical");
        assertThat(codes(judgement.judge(set(baby).temperatureCelsius(37.8).build())))
            .contains("newborn-temperature-who-danger");
    }

    @Test
    @DisplayName("D1564: HR 190, RR 70 and SpO2 88 raise the newborn findings, not only the adult flag")
    void theHighReadingsRaiseTheNewbornFindings() {
        Patient baby = bornDaysAgo(4);
        assertThat(codes(judgement.judge(set(baby).heartRateBpm(190).build())))
            .contains("newborn-heart-rate", "newborn-reference-heart-rate");
        assertThat(codes(judgement.judge(set(baby).respiratoryRateBpm(70).build()))).contains("newborn-respiratory");
        assertThat(codes(judgement.judge(set(baby).spo2Percent(88).build()))).contains("newborn-oxygenation");
    }

    @Test
    void aNormalNewbornSetIsNotSignificant() {
        PatientVitalSign vital = set(bornDaysAgo(4)).heartRateBpm(140).respiratoryRateBpm(45)
            .temperatureCelsius(36.8).spo2Percent(97).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);
        assertThat(j.newborn()).isTrue();
        assertThat(j.newbornFindings()).isEmpty();
        assertThat(vital.isClinicallySignificant()).isFalse();
    }

    @Test
    void aReferenceRangeNoticeAloneIsNotSignificant() {
        PatientVitalSign vital = set(bornDaysAgo(4)).heartRateBpm(115).build();
        assertThat(codes(judgement.judge(vital))).containsExactly("newborn-reference-heart-rate");
        assertThat(vital.isClinicallySignificant()).isFalse();
    }

    @Test
    @DisplayName("an extreme newborn reading is saved with its warnings: never refused")
    void anExtremeReadingIsNeverRefused() {
        PatientVitalSign vital = set(bornDaysAgo(1)).heartRateBpm(320).respiratoryRateBpm(200).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);
        assertThat(codes(j)).contains("newborn-check-heart-rate", "newborn-check-respiratory");
        assertThat(vital.isClinicallySignificant()).isTrue();
    }

    @Test
    @DisplayName("28 days and older keep the per-vital limits; HR 80 is not significant for an adult")
    void otherAgesKeepTheirRules() {
        PatientVitalSign dayTwentyEight = set(bornDaysAgo(28)).heartRateBpm(80).build();
        assertThat(judgement.judge(dayTwentyEight).newborn()).isFalse();
        assertThat(dayTwentyEight.isClinicallySignificant()).isFalse();

        Patient adult = new Patient();
        adult.setDateOfBirth(LocalDate.of(1980, 1, 1));
        PatientVitalSign fast = set(adult).heartRateBpm(160).build();
        assertThat(judgement.judge(fast).newborn()).isFalse();
        assertThat(fast.isClinicallySignificant()).as("HR above 150, the station's limit").isTrue();

        PatientVitalSign unknownAge = set(new Patient()).heartRateBpm(80).build();
        assertThat(judgement.judge(unknownAge).newborn()).as("no date of birth: never a newborn").isFalse();
    }

    @Test
    void aCallersFlagIsNeverCleared() {
        PatientVitalSign vital = set(bornDaysAgo(4)).heartRateBpm(140).clinicallySignificant(true).build();
        judgement.judge(vital);
        assertThat(vital.isClinicallySignificant()).isTrue();
    }

    @Test
    void pretermIsReadFromThisHospitalsDeliveryRecord() {
        Patient baby = bornDaysAgo(4);
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        NewbornAssessment assessment = NewbornAssessment.builder().hospital(hospital)
            .deliveryRecord(DeliveryRecord.builder().gestationalAgeWeeksAtBirth(33).build()).build();
        when(assessments.findFirstByPatient_IdAndHospital_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(
            baby.getId(), hospital.getId())).thenReturn(Optional.of(assessment));
        PatientVitalSign vital = set(baby).hospital(hospital).heartRateBpm(115).build();
        assertThat(codes(judgement.judge(vital))).containsExactly("newborn-reference-heart-rate-preterm");

        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        PatientVitalSign elsewhere = set(baby).hospital(other).heartRateBpm(115).build();
        assertThat(codes(judgement.judge(elsewhere))).containsExactly("newborn-reference-heart-rate");
    }

    @Test
    @DisplayName("an earlier term assessment at another hospital does not hide this hospital's preterm record")
    void aForeignEarlierAssessmentDoesNotHideThisHospitalsPretermRecord() {
        Patient baby = bornDaysAgo(4);
        Hospital here = new Hospital();
        here.setId(UUID.randomUUID());
        Hospital foreign = new Hospital();
        foreign.setId(UUID.randomUUID());
        NewbornAssessment foreignTerm = NewbornAssessment.builder().hospital(foreign)
            .assessmentTime(NOW.minusDays(4))
            .deliveryRecord(DeliveryRecord.builder().gestationalAgeWeeksAtBirth(39).build()).build();
        NewbornAssessment localPreterm = NewbornAssessment.builder().hospital(here)
            .assessmentTime(NOW.minusDays(3))
            .deliveryRecord(DeliveryRecord.builder().gestationalAgeWeeksAtBirth(33).build()).build();
        // The unscoped finder answers with the earliest row anywhere: the foreign term one.
        when(assessments.findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(baby.getId()))
            .thenReturn(Optional.of(foreignTerm));
        when(assessments.findFirstByPatient_IdAndHospital_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(
            baby.getId(), here.getId())).thenReturn(Optional.of(localPreterm));

        PatientVitalSign vital = set(baby).hospital(here).heartRateBpm(115).build();
        assertThat(codes(judgement.judge(vital))).containsExactly("newborn-reference-heart-rate-preterm");
    }

    @Test
    @DisplayName("each urgent finding notifies the recorder and the physicians, as the newborn assessment does")
    void urgentFindingsNotifyTheTeam() {
        Patient baby = bornDaysAgo(4);
        UUID hospitalId = UUID.randomUUID();
        Set<String> recipients = new LinkedHashSet<>(List.of("nurse_a", "dr_a"));
        when(news2.recipients(baby.getId(), hospitalId, "nurse_a", null)).thenReturn(recipients);
        PatientVitalSign vital = set(baby).heartRateBpm(80).spo2Percent(88).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);

        judgement.escalate(vital, j, baby.getId(), hospitalId, "nurse_a", null);

        verify(news2).escalateIfNeeded(vital, baby.getId(), hospitalId, "nurse_a", null);
        // Two urgent findings (heart rate, oxygenation) to two people.
        verify(notifications, times(4)).createNotification(eq("newborn.alert.notification"), any(),
            anyString(), eq("MATERNITY_ALERT"));
        verify(notifications, times(2)).createNotification(eq("newborn.alert.notification"), any(),
            eq("dr_a"), eq("MATERNITY_ALERT"));
    }

    @Test
    void nothingUrgentNotifiesNobody() {
        Patient baby = bornDaysAgo(4);
        PatientVitalSign vital = set(baby).heartRateBpm(115).build();
        judgement.escalate(vital, judgement.judge(vital), baby.getId(), UUID.randomUUID(), "nurse_a", null);
        verify(notifications, never()).createNotification(anyString(), any(), anyString(), anyString());
    }

    @Test
    void theAlertsAreInTheRecordersLanguage() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        PatientVitalSign vital = set(bornDaysAgo(4)).heartRateBpm(80).build();
        List<NewbornVitalAlertDTO> alerts = judgement.alerts(judgement.judge(vital));
        assertThat(alerts).extracting(NewbornVitalAlertDTO::getCode).contains("newborn-heart-rate");
        NewbornVitalAlertDTO urgent = alerts.stream().filter(a -> "newborn-heart-rate".equals(a.getCode()))
            .findFirst().orElseThrow();
        assertThat(urgent.getSeverity()).isEqualTo("URGENT");
        assertThat(urgent.getMessage()).isNotBlank().isNotEqualTo("newborn-heart-rate").contains("160");
        assertThat(judgement.alerts(judgement.judge(set(bornDaysAgo(400)).heartRateBpm(80).build()))).isEmpty();
    }

    /* ── Polish 2026-11-13: per-value flags for the after-visit summary ── */

    private static Patient adult() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDateOfBirth(LocalDate.of(1970, 3, 15));
        return patient;
    }

    @Test
    @DisplayName("polish: 142/88 is judged by no rule, so it carries no flag; 210 and SpO2 86 do")
    void valueFlagsFollowTheAdultLimits() {
        Patient p = adult();
        assertThat(VitalSignJudgement.valueFlags(set(p).systolicBpMmHg(142).diastolicBpMmHg(88).heartRateBpm(72)
            .weightKg(70.0).heightCm(165.0).build(), p.getDateOfBirth())).isEmpty();
        assertThat(VitalSignJudgement.valueFlags(set(p).systolicBpMmHg(210).diastolicBpMmHg(88).spo2Percent(86)
            .temperatureCelsius(34.5).build(), p.getDateOfBirth()))
            .containsEntry("systolicBpMmHg", "HIGH")
            .containsEntry("spo2Percent", "LOW")
            .containsEntry("temperatureCelsius", "LOW")
            .doesNotContainKey("diastolicBpMmHg");
    }

    @Test
    @DisplayName("polish: a newborn's values are flagged by the newborn rules, never by a reference notice alone")
    void valueFlagsFollowTheNewbornRules() {
        Patient baby = bornDaysAgo(4);
        assertThat(VitalSignJudgement.valueFlags(set(baby).heartRateBpm(80).build(), baby.getDateOfBirth()))
            .containsEntry("heartRateBpm", "ABNORMAL");
        assertThat(VitalSignJudgement.valueFlags(set(baby).heartRateBpm(115).build(), baby.getDateOfBirth()))
            .isEmpty();
    }

    /**
     * Guard: the per-value flags never disagree with the judgement of the set.
     * A value is flagged only when the same rules mark the set significant, and
     * a set marked significant by a per-value rule has a flagged value (a NEWS2
     * total alone flags the set, not a value).
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "4, 80, 36.8, 40, 97", "4, 140, 35.0, 45, 97", "4, 140, 36.8, 70, 88", "4, 115, 36.8, 45, 97",
        "20000, 30, 36.8, 16, 97", "20000, 72, 41.0, 16, 97", "20000, 72, 36.8, 35, 97",
        "20000, 72, 36.8, 16, 85", "20000, 72, 36.8, 16, 97"})
    @DisplayName("guard: a value is flagged exactly when the rules that judge the set flag it")
    void valueFlagsAgreeWithTheJudgement(int ageDays, int heartRate, double temperature, int respiratory,
                                         int spo2) {
        Patient p = bornDaysAgo(ageDays);
        PatientVitalSign vital = set(p).heartRateBpm(heartRate).temperatureCelsius(temperature)
            .respiratoryRateBpm(respiratory).spo2Percent(spo2).build();
        java.util.Map<String, String> flags = VitalSignJudgement.valueFlags(vital, p.getDateOfBirth());
        VitalSignJudgement.Judgement j = judgement.judge(vital);
        if (j.newborn()) {
            assertThat(!flags.isEmpty()).as("flags %s", flags).isEqualTo(j.newbornSignificant());
        } else if (!(com.example.hms.utility.NewsScoreCalculator.applies(vital)
                && com.example.hms.utility.NewsScoreCalculator.score(vital).total() >= 5)) {
            assertThat(!flags.isEmpty()).as("flags %s", flags).isEqualTo(vital.isClinicallySignificant());
        }
        if (!flags.isEmpty()) {
            assertThat(vital.isClinicallySignificant()).isTrue();
        }
    }
    // ---- QA D1890 (provisional, 2026-11-14): severe blood pressure after a recorded birth ----

    private Patient motherDeliveredBefore(boolean delivered) {
        Patient mother = new Patient();
        mother.setId(UUID.randomUUID());
        mother.setFirstName("Awa");
        mother.setLastName("Sawadogo");
        mother.setDateOfBirth(LocalDate.of(1995, 3, 1));
        if (delivered) {
            SevereHypertensionFixtures.bornOn(deliveries, mother.getId(), HERE, NOW.minusDays(3));
        }
        return mother;
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}/{1} after birth -> warning {2}")
    @org.junit.jupiter.params.provider.CsvSource({
        "162,112,true", "164,114,true", "160,80,true", "120,110,true", "159,109,false"
    })
    @DisplayName("D1890: a nurse-station set after birth is judged by the shared postpartum rule")
    void aSevereSetAfterBirthIsSignificantAndWarns(int systolic, int diastolic, boolean warns) {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        PatientVitalSign vital = bpSet(motherDeliveredBefore(true)).systolicBpMmHg(systolic).diastolicBpMmHg(diastolic).build();

        VitalSignJudgement.Judgement j = judgement.judge(vital);

        assertThat(j.postpartumSevereHypertension()).isEqualTo(warns);
        assertThat(vital.isClinicallySignificant()).isEqualTo(warns);
        List<NewbornVitalAlertDTO> alerts = judgement.postpartumAlerts(j);
        if (warns) {
            assertThat(alerts).singleElement().satisfies(a -> {
                assertThat(a.getCode()).isEqualTo("postpartum-severe-hypertension");
                assertThat(a.getSeverity()).isEqualTo("URGENT");
                assertThat(a.getType()).isEqualTo("HYPERTENSION");
                assertThat(a.getMessage()).contains("avis obstétrical urgent requis");
            });
        } else {
            assertThat(alerts).isEmpty();
        }
        assertThat(judgement.alerts(j)).as("never a newborn warning").isEmpty();
    }

    @Test
    void withNoRecordedBirthTheSameSetRaisesNoPostpartumWarning() {
        PatientVitalSign vital = bpSet(motherDeliveredBefore(false)).systolicBpMmHg(164).diastolicBpMmHg(114).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);
        assertThat(j.postpartumSevereHypertension()).isFalse();
        assertThat(judgement.postpartumAlerts(j)).isEmpty();
    }

    @Test
    void thePostpartumWarningNotifiesTheRecorderAndThePhysicians() {
        Patient mother = motherDeliveredBefore(true);
        UUID hospitalId = UUID.randomUUID();
        when(news2.recipients(mother.getId(), hospitalId, "nurse_a", null))
            .thenReturn(new LinkedHashSet<>(List.of("nurse_a", "dr_a")));
        PatientVitalSign vital = bpSet(mother).systolicBpMmHg(164).diastolicBpMmHg(114).build();

        judgement.escalate(vital, judgement.judge(vital), mother.getId(), hospitalId, "nurse_a", null);

        verify(notifications, times(2)).createNotification(eq("postpartum.alert.notification"), any(),
            anyString(), eq("MATERNITY_ALERT"));
        verify(notifications).createNotification(eq("postpartum.alert.notification"), any(), eq("dr_a"),
            eq("MATERNITY_ALERT"));
    }

    // ---- r54 adult (provisional, 2026-11-15): severe blood pressure in an adult with no obstetric scope ----

    @org.junit.jupiter.params.ParameterizedTest(name = "adult {0}/{1} -> warning {2}")
    @org.junit.jupiter.params.provider.CsvSource({
        "180,80,true", "120,120,true", "179,119,false", "164,114,false"
    })
    @DisplayName("r54: an adult woman with no delivery is judged by the adult rule (the QA observation: 164/114)")
    void anAdultWithNoDeliveryIsJudgedByTheAdultRule(int systolic, int diastolic, boolean warns) {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        PatientVitalSign vital = bpSet(motherDeliveredBefore(false)).systolicBpMmHg(systolic).diastolicBpMmHg(diastolic)
            .build();

        VitalSignJudgement.Judgement j = judgement.judge(vital);

        assertThat(j.adultSevereHypertension()).isEqualTo(warns);
        assertThat(j.postpartumSevereHypertension()).isFalse();
        assertThat(judgement.postpartumAlerts(j)).isEmpty();
        if (warns) {
            assertThat(vital.isClinicallySignificant()).isTrue();
            assertThat(judgement.hypertensionAlerts(j)).singleElement().satisfies(a -> {
                assertThat(a.getCode()).isEqualTo("adult-severe-hypertension");
                assertThat(a.getSeverity()).isEqualTo("WARNING");
                assertThat(a.getType()).isEqualTo("HYPERTENSION");
                assertThat(a.getMessage()).startsWith("Hypertension sévère — recontrôler la tension au repos")
                    .contains("d'atteinte d'organe");
            });
        } else {
            assertThat(judgement.hypertensionAlerts(j)).isEmpty();
        }
    }

    @Test
    void aSignOfOrganDamageMakesTheAdultWarningUrgentAndNotifies() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        Patient adult = motherDeliveredBefore(false);
        UUID hospitalId = UUID.randomUUID();
        when(news2.recipients(adult.getId(), hospitalId, "nurse_a", null))
            .thenReturn(new LinkedHashSet<>(List.of("nurse_a", "dr_a")));
        PatientVitalSign vital = bpSet(adult).systolicBpMmHg(190).diastolicBpMmHg(100)
            .organDamageSymptoms("CHEST_PAIN").build();

        VitalSignJudgement.Judgement j = judgement.judge(vital);
        assertThat(judgement.hypertensionAlerts(j)).singleElement()
            .satisfies(a -> assertThat(a.getSeverity()).isEqualTo("URGENT"));
        judgement.escalate(vital, j, adult.getId(), hospitalId, "nurse_a", null);

        verify(notifications, times(2)).createNotification(eq("vitals.alert.notification"), any(), anyString(),
            eq("SEVERE_HYPERTENSION"));
    }

    @Test
    void aPlainAdultWarningNotifiesNoOne() {
        Patient adult = motherDeliveredBefore(false);
        PatientVitalSign vital = bpSet(adult).systolicBpMmHg(185).diastolicBpMmHg(100).build();
        judgement.escalate(vital, judgement.judge(vital), adult.getId(), UUID.randomUUID(), "nurse_a", null);
        verify(notifications, never()).createNotification(eq("vitals.alert.notification"), any(), anyString(), anyString());
    }

    @Test
    void underEighteenTheAdultRuleIsSilent() {
        Patient teen = bornDaysAgo(16 * 365);
        teen.setId(UUID.randomUUID());
        PatientVitalSign vital = bpSet(teen).systolicBpMmHg(185).diastolicBpMmHg(125).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);
        assertThat(j.adultSevereHypertension()).isFalse();
        assertThat(judgement.hypertensionAlerts(j)).isEmpty();
    }

    /**
     * r54 review P3: a pregnant patient's severe-pressure notice was headed "Postpartum alert". It is
     * a pregnancy notice; the postpartum one stays for a mother after birth.
     */
    @Test
    void aPregnantPatientsNoticeIsAPregnancyNoticeNeverPostpartum() {
        com.example.hms.repository.MaternalHistoryRepository histories =
            mock(com.example.hms.repository.MaternalHistoryRepository.class);
        VitalSignJudgement withPregnancy = new VitalSignJudgement(assessments, news2, notifications,
            com.example.hms.i18n.TestMessageSources.bundles(), deliveryNewborns,
            new SevereHypertension(deliveries, histories, null, null, null));
        Patient pregnant = motherDeliveredBefore(false);
        com.example.hms.model.MaternalHistory history = new com.example.hms.model.MaternalHistory();
        history.setEstimatedDueDate(NOW.toLocalDate().plusWeeks(12));
        when(histories.findFirstByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(
            eq(pregnant.getId()), any())).thenReturn(Optional.of(history));
        UUID hospitalId = UUID.randomUUID();
        when(news2.recipients(pregnant.getId(), hospitalId, "nurse_a", null))
            .thenReturn(new LinkedHashSet<>(List.of("nurse_a")));
        PatientVitalSign vital = bpSet(pregnant).systolicBpMmHg(164).diastolicBpMmHg(114).build();

        VitalSignJudgement.Judgement j = withPregnancy.judge(vital);
        assertThat(j.bloodPressure().scope()).isEqualTo(SevereHypertension.Scope.PREGNANT);
        withPregnancy.escalate(vital, j, pregnant.getId(), hospitalId, "nurse_a", null);

        verify(notifications).createNotification(eq("vitals.alert.notification.pregnancy"), any(), eq("nurse_a"),
            eq("MATERNITY_ALERT"));
        verify(notifications, never()).createNotification(eq("postpartum.alert.notification"), any(), anyString(),
            anyString());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(com.example.hms.i18n.TestMessageSources.bundles().getMessage("vitals.alert.notification.pregnancy",
            new Object[] {"t", "p", "m"}, Locale.FRENCH)).startsWith("Alerte de grossesse").doesNotContain("post-partum");
    }

    @Test
    void review1237_theNews2PageIsSkippedOnlyWhenAnUrgentMaternityAlertCoveredTheReading() {
        com.example.hms.model.PatientVitalSign vital = com.example.hms.model.PatientVitalSign.builder()
            .systolicBpMmHg(88).heartRateBpm(132).build();
        java.util.UUID patientId = java.util.UUID.randomUUID();
        java.util.UUID hospitalId = java.util.UUID.randomUUID();

        judgement.escalate(vital, null, patientId, hospitalId, "mw", null, true);
        org.mockito.Mockito.verify(news2, org.mockito.Mockito.never())
            .escalateIfNeeded(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        judgement.escalate(vital, null, patientId, hospitalId, "mw", null, false);
        org.mockito.Mockito.verify(news2).escalateIfNeeded(vital, patientId, hospitalId, "mw", null);

        judgement.escalate(vital, null, patientId, hospitalId, "mw", null);
        org.mockito.Mockito.verify(news2, org.mockito.Mockito.times(2)).escalateIfNeeded(vital, patientId, hospitalId, "mw", null);
    }

    // ---- Policy 2026-11-20 (provisional): the maternal early warning of a pregnant or postpartum woman ----

    private final com.example.hms.repository.StaffRepository maternityStaff =
        mock(com.example.hms.repository.StaffRepository.class);

    /** The real service on the shared obstetric rule, paging through this test's notification mock. */
    private void withTheMaternalWarning() {
        when(maternityStaff.findActiveUsernamesByHospitalAndRole(HERE, "ROLE_MIDWIFE")).thenReturn(List.of("sf_a"));
        when(maternityStaff.findActiveUsernamesByHospitalAndRole(HERE, "ROLE_DOCTOR")).thenReturn(List.of("dr_a", "dr_b"));
        when(news2.recipients(any(), any(), any(), any()))
            .thenReturn(new LinkedHashSet<>(List.of("nurse_a", "dr_a")));
        judgement.setMaternalEarlyWarning(new MaternalEarlyWarningService(new MaternalEarlyWarningRules(),
            new News2Applicability(SevereHypertensionFixtures.actingHospitalOnly(deliveries)), news2, maternityStaff,
            mock(com.example.hms.repository.OnCallScheduleRepository.class),
            mock(com.example.hms.repository.AdmissionRepository.class), notifications, java.time.Clock.systemUTC()));
    }

    private static PatientVitalSign.PatientVitalSignBuilder calm(PatientVitalSign.PatientVitalSignBuilder set) {
        return set.respiratoryRateBpm(16).spo2Percent(98).temperatureCelsius(36.8).heartRateBpm(80)
            .systolicBpMmHg(118).diastolicBpMmHg(74);
    }

    @Test
    @DisplayName("policy 2026-11-20: for a postpartum mother the maternal warning pages, NEWS2 never does")
    void policy20261120_theMaternalWarningReplacesTheNews2Page() {
        withTheMaternalWarning();
        Patient mother = motherDeliveredBefore(true);
        // Three days after the birth the pulse is read on the post-birth table: 110 scores 2 (low-medium).
        PatientVitalSign vital = calm(bpSet(mother)).heartRateBpm(110).build();

        VitalSignJudgement.Judgement j = judgement.judge(vital);

        assertThat(j.maternal()).isNotNull();
        assertThat(j.maternal().total()).isEqualTo(2);
        assertThat(j.maternal().postBirthPulse()).isTrue();
        assertThat(j.maternal().tier()).isEqualTo(MaternalEarlyWarning.Tier.LOW_MEDIUM);
        assertThat(vital.isClinicallySignificant()).isTrue();

        judgement.escalate(vital, j, mother.getId(), HERE, "nurse_a", null);

        verify(news2, never()).escalateIfNeeded(any(), any(), any(), any(), any());
        for (String reader : List.of("nurse_a", "dr_a", "sf_a", "dr_b")) {
            verify(notifications).createNotification(eq("mews.escalation.lowMedium"), any(), eq(reader),
                eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        }
    }

    @Test
    @DisplayName("policy 2026-11-20: a calm set of a postpartum mother pages nobody and shows no reassuring NEWS2 page")
    void policy20261120_aLowMaternalScorePagesNobody() {
        withTheMaternalWarning();
        Patient mother = motherDeliveredBefore(true);
        PatientVitalSign vital = calm(bpSet(mother)).build();

        VitalSignJudgement.Judgement j = judgement.judge(vital);
        judgement.escalate(vital, j, mother.getId(), HERE, "nurse_a", null);

        assertThat(j.maternal().tier()).isEqualTo(MaternalEarlyWarning.Tier.LOW);
        assertThat(vital.isClinicallySignificant()).isFalse();
        verify(news2, never()).escalateIfNeeded(any(), any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("policy 2026-11-20: one reading never pages a person twice (severe pressure notice, then the maternal page)")
    void policy20261120_oneReadingNeverDoublePages() {
        withTheMaternalWarning();
        Patient mother = motherDeliveredBefore(true);
        PatientVitalSign vital = calm(bpSet(mother)).systolicBpMmHg(164).diastolicBpMmHg(114).build();

        VitalSignJudgement.Judgement j = judgement.judge(vital);
        judgement.escalate(vital, j, mother.getId(), HERE, "nurse_a", null);

        // The severe-pressure notice reaches the recorder and the responsible physician, as before; review of
        // #1241: it carries her score and the level it is answered at, since they get no second notice.
        org.mockito.ArgumentCaptor<Object[]> pressureArgs = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(notifications).createNotification(eq("postpartum.alert.notification.mews"), pressureArgs.capture(),
            eq("nurse_a"), eq("MATERNITY_ALERT"));
        verify(notifications).createNotification(eq("postpartum.alert.notification.mews"), any(), eq("dr_a"),
            eq("MATERNITY_ALERT"));
        assertThat(pressureArgs.getValue()).hasSize(5);
        assertThat(pressureArgs.getValue()[3]).isEqualTo(4);
        assertThat(String.valueOf(pressureArgs.getValue()[4])).contains("cds.bpa.mews.tier.HIGH");
        assertThat(com.example.hms.i18n.TestMessageSources.bundles().getMessage("postpartum.alert.notification.mews",
            new Object[] {"hypertension sévère", "Awa Sawadogo", "Recontrôler la tension.", 4, "préoccupation élevée"},
            Locale.FRENCH)).isEqualTo("Alerte post-partum (hypertension sévère) pour Awa Sawadogo : "
                + "Recontrôler la tension. MEWS 4 (préoccupation élevée), score provisoire.");
        // The maternal page (red: the high circle) reaches only those the notice did not.
        verify(notifications).createNotification(eq("mews.escalation.red"), any(), eq("sf_a"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        verify(notifications).createNotification(eq("mews.escalation.red"), any(), eq("dr_b"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        verify(notifications, never()).createNotification(org.mockito.ArgumentMatchers.startsWith("mews."), any(),
            eq("nurse_a"), anyString());
        verify(notifications, never()).createNotification(org.mockito.ArgumentMatchers.startsWith("mews."), any(),
            eq("dr_a"), anyString());
        // Four people, one notice each.
        verify(notifications, times(4)).createNotification(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("policy 2026-11-20: a reading an URGENT maternity alert already paged for is not paged again")
    void policy20261120_theMaternityAlertDedupeIsKept() {
        withTheMaternalWarning();
        Patient mother = motherDeliveredBefore(true);
        PatientVitalSign vital = calm(bpSet(mother)).heartRateBpm(150).systolicBpMmHg(85).build();
        VitalSignJudgement.Judgement charted = judgement.judge(vital).maternalOnly();
        assertThat(charted.maternal().red()).isTrue();

        judgement.escalate(vital, charted, mother.getId(), HERE, "mw", null, true);
        org.mockito.Mockito.verifyNoInteractions(notifications);
        verify(news2, never()).escalateIfNeeded(any(), any(), any(), any(), any());

        // With no URGENT maternity alert, the maternal page is the one that goes (in labour: a low pressure, a fast pulse).
        judgement.escalate(vital, charted, mother.getId(), HERE, "mw", null, false);
        verify(notifications).createNotification(eq("mews.escalation.red"), any(), eq("sf_a"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        verify(news2, never()).escalateIfNeeded(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("policy 2026-11-20: a woman who is neither pregnant nor postpartum keeps NEWS2 and gets no maternal score")
    void policy20261120_anAdultOutsideTheScopeKeepsNews2() {
        withTheMaternalWarning();
        Patient adult = motherDeliveredBefore(false);
        PatientVitalSign vital = calm(bpSet(adult)).heartRateBpm(125).build();

        VitalSignJudgement.Judgement j = judgement.judge(vital);
        judgement.escalate(vital, j, adult.getId(), HERE, "nurse_a", null);

        assertThat(j.maternal()).isNull();
        assertThat(judgement.maternalAlerts(j)).isEmpty();
        verify(news2).escalateIfNeeded(vital, adult.getId(), HERE, "nurse_a", null);
        verify(notifications, never()).createNotification(org.mockito.ArgumentMatchers.startsWith("mews."), any(),
            anyString(), anyString());
    }

    @Test
    @DisplayName("policy 2026-11-20: the recorder reads the level of concern and the shock alert, in her language")
    void policy20261120_theRecorderReadsTheMaternalWarning() {
        withTheMaternalWarning();
        LocaleContextHolder.setLocale(Locale.FRENCH);
        Patient mother = motherDeliveredBefore(true);
        // Pulse 112 over 118: 2 points on the post-birth table, and a shock index of 0.95 (yellow).
        PatientVitalSign vital = calm(bpSet(mother)).heartRateBpm(112).build();

        List<NewbornVitalAlertDTO> alerts = judgement.maternalAlerts(judgement.judge(vital));

        assertThat(alerts).extracting(NewbornVitalAlertDTO::getCode).containsExactly("mews-low-medium", "maternal-shock");
        assertThat(alerts.get(0).getSeverity()).isEqualTo("WARNING");
        assertThat(alerts.get(0).getMessage()).contains("Score d'alerte précoce maternelle 2").contains("sous 30 min");
        assertThat(alerts.get(1).getMessage()).contains("Indice de choc").contains("zone jaune");
        assertThat(judgement.postpartumAlerts(judgement.judge(vital))).as("no severe pressure here").isEmpty();

        // A red alert is answered as high: the recorder reads « revue immédiate », not the 30 minutes of a score of 4.
        PatientVitalSign severe = calm(bpSet(mother)).systolicBpMmHg(164).diastolicBpMmHg(114).build();
        List<NewbornVitalAlertDTO> red = judgement.maternalAlerts(judgement.judge(severe));
        assertThat(red).singleElement().satisfies(a -> {
            assertThat(a.getCode()).isEqualTo("mews-high");
            assertThat(a.getSeverity()).isEqualTo("URGENT");
            assertThat(a.getMessage()).contains("revue immédiate");
        });
    }

    // ---- Review of #1241 (eb0e8566) ----

    @Test
    @DisplayName("review of #1241: FHR 100 with the mother at pulse 124, 92/55, 37.6 °C: her MEWS high page is still sent")
    void review1241_aFetalUrgentAlertDoesNotSilenceTheMothersPage() {
        withTheMaternalWarning();
        Patient mother = motherDeliveredBefore(false);
        // A partograph row: pulse 2 + systolic 2 + diastolic 2 + temperature 2 = 8, high.
        PatientVitalSign vital = bpSet(mother).heartRateBpm(124).systolicBpMmHg(92).diastolicBpMmHg(55)
            .temperatureCelsius(37.6).build();
        MaternalEarlyWarning.Score score = MaternalEarlyWarning.score(new MaternalEarlyWarningRules(), vital, false);
        assertThat(score.total()).isEqualTo(8);
        assertThat(score.responseTier()).isEqualTo(MaternalEarlyWarning.Tier.HIGH);
        VitalSignJudgement.Judgement charted =
            new VitalSignJudgement.Judgement(false, List.of(), SevereHypertension.Verdict.NONE, score);

        // The fetal bradycardia paged the team about the baby (any URGENT), not about the mother.
        judgement.escalate(vital, charted, mother.getId(), HERE, "mw", null,
            new VitalSignJudgement.MaternityCover(true, false));

        verify(notifications).createNotification(eq("mews.escalation.high"), any(), eq("sf_a"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        verify(notifications).createNotification(eq("mews.escalation.high"), any(), eq("dr_b"),
            eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        verify(news2, never()).escalateIfNeeded(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("review of #1241: an URGENT alert about the mother's own values still covers her page; NEWS2 is covered by any URGENT alert")
    void review1241_onlyAnAlertAboutTheMotherCoversHerPage() {
        withTheMaternalWarning();
        Patient mother = motherDeliveredBefore(false);
        PatientVitalSign vital = bpSet(mother).heartRateBpm(124).systolicBpMmHg(92).diastolicBpMmHg(55)
            .temperatureCelsius(37.6).build();
        VitalSignJudgement.Judgement charted = new VitalSignJudgement.Judgement(false, List.of(),
            SevereHypertension.Verdict.NONE, MaternalEarlyWarning.score(new MaternalEarlyWarningRules(), vital, false));

        judgement.escalate(vital, charted, mother.getId(), HERE, "mw", null,
            new VitalSignJudgement.MaternityCover(true, true));
        verifyNoInteractions(notifications);

        // Without a maternal score (a patient outside the scope, or the rule set off) the NEWS2 page keeps
        // its #1237 cover: any URGENT maternity alert, a fetal one included.
        judgement.escalate(vital, null, mother.getId(), HERE, "mw", null,
            new VitalSignJudgement.MaternityCover(true, false));
        verify(news2, never()).escalateIfNeeded(any(), any(), any(), any(), any());
        judgement.escalate(vital, null, mother.getId(), HERE, "mw", null, VitalSignJudgement.MaternityCover.NONE);
        verify(news2).escalateIfNeeded(vital, mother.getId(), HERE, "mw", null);
    }

    // ---- Review of #1241, round 2 ----

    /** The maternal warning with a cool-down table that says: she was paged at every level a moment ago. */
    private void withARecentPageOnRecord() {
        when(maternityStaff.findActiveUsernamesByHospitalAndRole(HERE, "ROLE_MIDWIFE")).thenReturn(List.of("sf_a"));
        when(maternityStaff.findActiveUsernamesByHospitalAndRole(HERE, "ROLE_DOCTOR")).thenReturn(List.of("dr_a", "dr_b"));
        when(news2.recipients(any(), any(), any(), any()))
            .thenReturn(new LinkedHashSet<>(List.of("nurse_a", "dr_a")));
        MaternalEarlyWarningService service = new MaternalEarlyWarningService(new MaternalEarlyWarningRules(),
            new News2Applicability(SevereHypertensionFixtures.actingHospitalOnly(deliveries)), news2, maternityStaff,
            mock(com.example.hms.repository.OnCallScheduleRepository.class),
            mock(com.example.hms.repository.AdmissionRepository.class), notifications, java.time.Clock.systemUTC());
        com.example.hms.repository.PatientVitalSignRepository vitals =
            mock(com.example.hms.repository.PatientVitalSignRepository.class);
        when(vitals.existsByPatient_IdAndHospital_IdAndMewsPagedTierInAndMewsPagedAtGreaterThanEqual(any(), any(),
            any(), any())).thenReturn(true);
        service.setVitalSignRepository(vitals);
        judgement.setMaternalEarlyWarning(service);
    }

    @Test
    @DisplayName("round 2: a withheld page pages nobody and the recorder reads « déjà prévenue », with a distinct code")
    void round2_theRecorderIsToldWhenThePageWasWithheld() {
        withARecentPageOnRecord();
        LocaleContextHolder.setLocale(Locale.FRENCH);
        Patient mother = motherDeliveredBefore(true);
        // Not red: pulse 110 three days after the birth scores 2 (low-medium), no pressure alert.
        PatientVitalSign vital = calm(bpSet(mother)).heartRateBpm(110).systolicBpMmHg(130).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);
        assertThat(j.maternal().red()).isFalse();

        MaternalEarlyWarningService.PageStatus page =
            judgement.escalate(vital, j, mother.getId(), HERE, "nurse_a", null);

        assertThat(page).isEqualTo(MaternalEarlyWarningService.PageStatus.WITHHELD);
        verifyNoInteractions(notifications);
        List<NewbornVitalAlertDTO> alerts = judgement.maternalAlerts(j, page);
        assertThat(alerts).singleElement().satisfies(a -> {
            // Not the plain « mews-low-medium » the portal reads as « l'équipe soignante a été prévenue ».
            assertThat(a.getCode()).isEqualTo("mews-low-medium-withheld");
            assertThat(a.getMessage()).contains("Score d'alerte précoce maternelle 2")
                .contains("a déjà été prévenue il y a moins de 30 min")
                .contains("aucun nouvel appel n'a été envoyé");
        });
        // The score is still computed and the set still flagged: only the page was withheld.
        assertThat(j.maternal().total()).isEqualTo(2);
        assertThat(vital.isClinicallySignificant()).isTrue();
    }

    @Test
    @DisplayName("round 2: on a vitals path a severe shock pages although she was paged at high a moment ago")
    void round2_aSevereShockOnAVitalsPathIsNeverSilenced() {
        withARecentPageOnRecord();
        LocaleContextHolder.setLocale(Locale.FRENCH);
        Patient mother = motherDeliveredBefore(true);
        // Pulse 160 over 85/50: shock index 1.88.
        PatientVitalSign vital = calm(bpSet(mother)).heartRateBpm(160).systolicBpMmHg(85).diastolicBpMmHg(50).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);
        assertThat(j.maternal().alerts()).containsExactly(MaternalEarlyWarning.HardAlert.SEVERE_SHOCK);

        MaternalEarlyWarningService.PageStatus page =
            judgement.escalate(vital, j, mother.getId(), HERE, "nurse_a", null);

        assertThat(page).isEqualTo(MaternalEarlyWarningService.PageStatus.SENT);
        for (String reader : List.of("nurse_a", "dr_a", "sf_a", "dr_b")) {
            verify(notifications).createNotification(eq("mews.escalation.red"), any(), eq(reader),
                eq(MaternalEarlyWarningService.NOTIFICATION_TYPE));
        }
        // The recorder's warning keeps the plain code: the team WAS told.
        assertThat(judgement.maternalAlerts(j, page)).extracting(NewbornVitalAlertDTO::getCode)
            .containsExactly("mews-high", "maternal-severe-shock");
    }

    @Test
    @DisplayName("round 2: a due page that reached nobody is not called sent: the recorder is told to alert the team herself")
    void round2_aPageThatReachedNobodyIsSaidSo() {
        withTheMaternalWarning();
        LocaleContextHolder.setLocale(Locale.FRENCH);
        Patient mother = motherDeliveredBefore(true);
        PatientVitalSign vital = calm(bpSet(mother)).heartRateBpm(110).systolicBpMmHg(130).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);

        List<NewbornVitalAlertDTO> alerts = judgement.maternalAlerts(j, MaternalEarlyWarningService.PageStatus.NOBODY);

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.getCode()).isEqualTo("mews-low-medium-unsent");
            assertThat(a.getMessage()).contains("personne n'a pu être prévenu").contains("Prévenir directement");
        });
        // A page that was sent, or one nobody reported on, keeps the plain code and sentence.
        assertThat(judgement.maternalAlerts(j, MaternalEarlyWarningService.PageStatus.SENT))
            .extracting(NewbornVitalAlertDTO::getCode).containsExactly("mews-low-medium");
        assertThat(judgement.maternalAlerts(j)).extracting(NewbornVitalAlertDTO::getCode)
            .containsExactly("mews-low-medium");
    }
}
