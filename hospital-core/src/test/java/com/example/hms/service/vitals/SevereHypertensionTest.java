package com.example.hms.service.vitals;

import com.example.hms.enums.LaborStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.model.labor.LaborEpisode;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.LaborEpisodeRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.vitals.SevereHypertension.Level;
import com.example.hms.service.vitals.SevereHypertension.Scope;
import com.example.hms.service.vitals.SevereHypertension.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA D1890 and r54 (provisional, 2026-11-14/15): the one severe-blood-pressure rule. Obstetric
 * (pregnancy, 42 days after a birth) at &ge; 160/110 URGENT; adult at &ge; 180/120, WARNING, URGENT
 * with a sign of organ damage; nothing under 18 without a birth. Obstetric records are read only at
 * the hospitals the record-access policy allows, and a foreign one behind a warning is audited.
 */
class SevereHypertensionTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 15, 10, 30);
    /** The hospital the reading is taken at. */
    private static final UUID HERE = UUID.randomUUID();
    /** Another hospital of the network. */
    private static final UUID ELSEWHERE = UUID.randomUUID();

    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private final MaternalHistoryRepository histories = mock(MaternalHistoryRepository.class);
    private final LaborEpisodeRepository labours = mock(LaborEpisodeRepository.class);
    private final RecordAccessPolicy policy = mock(RecordAccessPolicy.class);
    private final CrossHospitalReachRecorder reach = mock(CrossHospitalReachRecorder.class);
    private final SevereHypertension rule = new SevereHypertension(deliveries, histories, labours, policy, reach);

    @BeforeEach
    void bothHospitalsReadable() {
        when(policy.readableHospitalIds(any(), any(), eq(HERE))).thenReturn(Set.of(HERE, ELSEWHERE));
    }

    private static Patient woman(LocalDate dateOfBirth) {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        p.setGender("F");
        p.setDateOfBirth(dateOfBirth);
        return p;
    }

    private static Patient adultWoman() {
        return woman(LocalDate.of(1990, 5, 1));
    }

    private static Hospital hospital(UUID id) {
        Hospital h = Hospital.builder().build();
        h.setId(id);
        return h;
    }

    private void bornOn(Patient p, LocalDateTime birth) {
        SevereHypertensionFixtures.bornOn(deliveries, p.getId(), HERE, birth);
    }

    /** A maternal history at {@code hospitalId} answered for the hospitals asked, as the real query is. */
    private void pregnantAt(Patient p, UUID hospitalId, LocalDate dueDate) {
        MaternalHistory history = new MaternalHistory();
        history.setEstimatedDueDate(dueDate);
        history.setHospital(hospital(hospitalId));
        when(histories.findFirstByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(eq(p.getId()), any()))
            .thenAnswer(inv -> ((Collection<?>) inv.getArgument(1)).contains(hospitalId)
                ? Optional.of(history) : Optional.empty());
    }

    private Verdict at(Patient p, int systolic, int diastolic) {
        return rule.evaluate(p, HERE, systolic, diastolic, AT, false);
    }

    @ParameterizedTest(name = "obstetric {0}/{1} -> {2}")
    @CsvSource({
        // The user's boundaries: each threshold alone, and just below both.
        "160,80,true", "120,110,true", "159,109,false", "164,114,true", "162,112,true",
        // Inclusive, either suffices, a missing value never triggers.
        "160,110,true", "159,110,true", "160,109,true", "200,,true", ",115,true", "159,,false", ",109,false", ",,false"
    })
    void theObstetricThresholds(Integer systolic, Integer diastolic, boolean severe) {
        assertThat(SevereHypertension.isObstetricSevere(systolic, diastolic)).isEqualTo(severe);
        assertThat(SevereHypertension.judge(Scope.POSTPARTUM, systolic, diastolic, false).level())
            .isEqualTo(severe ? Level.URGENT : Level.NONE);
        assertThat(SevereHypertension.judge(Scope.PREGNANT, systolic, diastolic, false).level())
            .isEqualTo(severe ? Level.URGENT : Level.NONE);
    }

    @ParameterizedTest(name = "adult {0}/{1} -> {2}")
    @CsvSource({
        "180,80,true", "120,120,true", "179,119,false", "164,114,false", "162,112,false", "160,110,false",
        "180,120,true", "179,120,true", "180,119,true", "220,,true", ",125,true", "179,,false", ",119,false", ",,false"
    })
    void theAdultThresholds(Integer systolic, Integer diastolic, boolean severe) {
        assertThat(SevereHypertension.isAdultSevere(systolic, diastolic)).isEqualTo(severe);
        assertThat(SevereHypertension.judge(Scope.ADULT, systolic, diastolic, false).level())
            .isEqualTo(severe ? Level.WARNING : Level.NONE);
        assertThat(SevereHypertension.judge(Scope.ADULT, systolic, diastolic, true).level())
            .as("a sign of organ damage makes it URGENT, never raises a reading below the thresholds")
            .isEqualTo(severe ? Level.URGENT : Level.NONE);
    }

    @Test
    @DisplayName("an adult woman with no birth and no pregnancy: 164/114 is not severe, 180/80 and 120/120 warn")
    void anAdultWithNoObstetricRecord() {
        Patient p = adultWoman();
        assertThat(at(p, 164, 114)).isEqualTo(Verdict.NONE);
        assertThat(at(p, 179, 119)).isEqualTo(Verdict.NONE);
        assertThat(at(p, 180, 80)).isEqualTo(new Verdict(Scope.ADULT, Level.WARNING));
        assertThat(at(p, 120, 120)).isEqualTo(new Verdict(Scope.ADULT, Level.WARNING));
        assertThat(rule.evaluate(p, HERE, 120, 120, AT, true)).isEqualTo(new Verdict(Scope.ADULT, Level.URGENT));
    }

    @Test
    @DisplayName("postpartum day 42 is obstetric, day 43 is adult")
    void thePostpartumWindowIs42Days() {
        Patient day42 = adultWoman();
        bornOn(day42, AT.toLocalDate().minusDays(42).atTime(23, 50));
        assertThat(rule.scopeOf(day42, HERE, AT)).isEqualTo(Scope.POSTPARTUM);
        assertThat(at(day42, 164, 114)).isEqualTo(new Verdict(Scope.POSTPARTUM, Level.URGENT));

        Patient day43 = adultWoman();
        bornOn(day43, AT.toLocalDate().minusDays(43).atTime(0, 10));
        assertThat(rule.scopeOf(day43, HERE, AT)).isEqualTo(Scope.ADULT);
        assertThat(at(day43, 164, 114)).as("adult rule: below 180/120").isEqualTo(Verdict.NONE);
        assertThat(at(day43, 180, 80)).isEqualTo(new Verdict(Scope.ADULT, Level.WARNING));
    }

    @Test
    @DisplayName("a pregnant patient (maternal history covering the reading) is under the obstetric rule")
    void aPregnantPatient() {
        Patient p = adultWoman();
        pregnantAt(p, HERE, AT.toLocalDate().plusWeeks(10));

        assertThat(rule.scopeOf(p, HERE, AT)).isEqualTo(Scope.PREGNANT);
        Verdict v = at(p, 160, 80);
        assertThat(v).isEqualTo(new Verdict(Scope.PREGNANT, Level.URGENT));
        assertThat(v.alertCode()).isEqualTo(SevereHypertension.PREGNANCY_ALERT_CODE);
        assertThat(at(p, 120, 110).obstetric()).isTrue();
        assertThat(at(p, 159, 109)).isEqualTo(Verdict.NONE);
        verify(reach, never()).recordReach(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void aPregnancyEndedByABirthIsNotPregnancy() {
        Patient p = adultWoman();
        pregnantAt(p, HERE, AT.toLocalDate().minusDays(5));
        // Born 60 days ago, within the dated pregnancy: neither pregnant nor postpartum any more.
        bornOn(p, AT.minusDays(60));
        assertThat(rule.scopeOf(p, HERE, AT)).isEqualTo(Scope.ADULT);
    }

    @Test
    void anActiveLabourIsPregnancy() {
        Patient p = adultWoman();
        LaborEpisode labour = LaborEpisode.builder().hospital(hospital(HERE)).build();
        when(labours.findFirstByPatient_IdAndHospital_IdInAndStatusOrderByAdmittedAtDesc(eq(p.getId()), any(),
            eq(LaborStatus.ACTIVE))).thenReturn(Optional.of(labour));
        assertThat(rule.scopeOf(p, HERE, AT)).isEqualTo(Scope.PREGNANT);
    }

    // ---- r54 review P1: another hospital's obstetric record, only through the record-access policy ----

    @Test
    @DisplayName("P1: a pregnancy recorded only at another hospital the patient withheld is not read: adult rule")
    void aWithheldPregnancyAtAnotherHospitalIsNotRevealed() {
        Patient p = adultWoman();
        // Sharing opted out: the policy lets this hospital read its own records only.
        when(policy.readableHospitalIds(any(), eq(p.getId()), eq(HERE))).thenReturn(Set.of(HERE));
        pregnantAt(p, ELSEWHERE, AT.toLocalDate().plusWeeks(10));
        LaborEpisode labourElsewhere = LaborEpisode.builder().hospital(hospital(ELSEWHERE)).build();
        when(labours.findFirstByPatient_IdAndHospital_IdInAndStatusOrderByAdmittedAtDesc(eq(p.getId()), any(),
            eq(LaborStatus.ACTIVE))).thenAnswer(inv -> ((Collection<?>) inv.getArgument(1)).contains(ELSEWHERE)
                ? Optional.of(labourElsewhere) : Optional.empty());

        Verdict v = at(p, 164, 114);

        assertThat(v).as("no obstetric threshold, no obstetric sentence").isEqualTo(Verdict.NONE);
        assertThat(rule.scopeOf(p, HERE, AT)).isEqualTo(Scope.ADULT);
        assertThat(at(p, 182, 100)).isEqualTo(new Verdict(Scope.ADULT, Level.WARNING));
        verify(reach, never()).recordReach(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("P1: sharing allowed, a pregnancy at another hospital raises the obstetric warning and is audited")
    void aSharedPregnancyAtAnotherHospitalIsReadAndAudited() {
        Patient p = adultWoman();
        pregnantAt(p, ELSEWHERE, AT.toLocalDate().plusWeeks(10));

        Verdict v = at(p, 164, 114);

        assertThat(v).isEqualTo(new Verdict(Scope.PREGNANT, Level.URGENT));
        verify(reach).recordReach(eq(p.getId()), eq(HERE), any(), any(), eq(Map.of(ELSEWHERE.toString(), 1L)),
            eq(SevereHypertension.REACH_DESCRIPTION));
    }

    @Test
    void withNoActingHospitalNoObstetricRecordIsRead() {
        Patient p = adultWoman();
        assertThat(rule.evaluate(p, null, 185, 100, AT, false).adult()).isTrue();
        verifyNoInteractions(deliveries, histories, labours, policy);
    }

    // ---- r54 review P2: a mother under 18 past the 42 days is covered by the adult thresholds ----

    @Test
    @DisplayName("P2: a 16-year-old mother on day 50 at 200/130 warns (adult thresholds)")
    void aTeenMotherPastThe42DaysIsCoveredByTheAdultRule() {
        Patient mother = woman(AT.toLocalDate().minusYears(16));
        bornOn(mother, AT.minusDays(50));
        assertThat(at(mother, 200, 130)).isEqualTo(new Verdict(Scope.ADULT, Level.WARNING));
        assertThat(at(mother, 170, 115)).as("below the adult thresholds").isEqualTo(Verdict.NONE);
    }

    @Test
    @DisplayName("P2: a 16-year-old who never gave birth at 200/130 is unchanged (no paediatric rule)")
    void aTeenWithNoBirthIsUntouched() {
        Patient teen = woman(AT.toLocalDate().minusYears(16));
        assertThat(at(teen, 200, 130)).isEqualTo(Verdict.NONE);
        assertThat(rule.evaluate(teen, HERE, 200, 130, AT, true)).isEqualTo(Verdict.NONE);
    }

    @Test
    @DisplayName("under 18: a pregnant 16-year-old is under the obstetric rule; 18 today is adult")
    void underEighteen() {
        Patient pregnantTeen = woman(AT.toLocalDate().minusYears(16));
        pregnantAt(pregnantTeen, HERE, AT.toLocalDate().plusWeeks(8));
        assertThat(at(pregnantTeen, 160, 80)).isEqualTo(new Verdict(Scope.PREGNANT, Level.URGENT));

        Patient eighteenToday = woman(AT.toLocalDate().minusYears(18));
        assertThat(at(eighteenToday, 180, 80).adult()).isTrue();
    }

    @Test
    void aRecordedManIsNeverUnderTheObstetricRule() {
        Patient man = adultWoman();
        man.setGender("M");
        assertThat(at(man, 170, 100)).isEqualTo(Verdict.NONE);
        assertThat(at(man, 185, 100).adult()).isTrue();
        verifyNoInteractions(deliveries, histories, labours, policy);
    }

    @Test
    void anUnknownBirthDateReadsAsAdult() {
        assertThat(at(woman(null), 180, 80).adult()).isTrue();
    }

    @Test
    void aReadingBelowTheObstetricThresholdsNeverQueriesTheRecord() {
        at(adultWoman(), 159, 109);
        verifyNoInteractions(deliveries, histories, labours, policy, reach);
    }

    @Test
    void theMessagesFollowTheScopeAndLevel() {
        assertThat(new Verdict(Scope.POSTPARTUM, Level.URGENT).messageKey()).isEqualTo(SevereHypertension.MESSAGE_KEY);
        assertThat(new Verdict(Scope.PREGNANT, Level.URGENT).messageKey()).isEqualTo(SevereHypertension.PREGNANCY_MESSAGE_KEY);
        assertThat(new Verdict(Scope.ADULT, Level.WARNING).messageKey()).isEqualTo(SevereHypertension.ADULT_MESSAGE_KEY);
        assertThat(new Verdict(Scope.ADULT, Level.URGENT).messageKey()).isEqualTo(SevereHypertension.ADULT_URGENT_MESSAGE_KEY);
    }
}
