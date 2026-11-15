package com.example.hms.service.vitals;

import com.example.hms.enums.LaborStatus;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.LaborEpisodeRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.service.vitals.SevereHypertension.Level;
import com.example.hms.service.vitals.SevereHypertension.Scope;
import com.example.hms.service.vitals.SevereHypertension.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA D1890 and r54 (provisional, 2026-11-14/15): the one severe-blood-pressure rule. Obstetric
 * (pregnancy, 42 days after a birth) at &ge; 160/110 URGENT; adult at &ge; 180/120, WARNING, URGENT
 * with a sign of organ damage; nothing under 18 outside the obstetric scope.
 */
class SevereHypertensionTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 15, 10, 30);

    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private final MaternalHistoryRepository histories = mock(MaternalHistoryRepository.class);
    private final LaborEpisodeRepository labours = mock(LaborEpisodeRepository.class);
    private final SevereHypertension rule = new SevereHypertension(deliveries, histories, labours);

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

    /** A birth on record for this patient: the repository answers for the exact window it is asked. */
    private void bornOn(Patient p, LocalDateTime birth) {
        when(deliveries.existsByPatient_IdAndBirthDateTimeBetween(eq(p.getId()), any(), any())).thenAnswer(inv -> {
            LocalDateTime from = inv.getArgument(1);
            LocalDateTime to = inv.getArgument(2);
            return !birth.isBefore(from) && !birth.isAfter(to);
        });
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
        bornOn(p, AT.minusYears(5));
        when(histories.findCurrentByPatientId(p.getId())).thenReturn(Optional.empty());

        assertThat(rule.evaluate(p, 164, 114, AT, false)).isEqualTo(Verdict.NONE);
        assertThat(rule.evaluate(p, 179, 119, AT, false)).isEqualTo(Verdict.NONE);
        assertThat(rule.evaluate(p, 180, 80, AT, false)).isEqualTo(new Verdict(Scope.ADULT, Level.WARNING));
        assertThat(rule.evaluate(p, 120, 120, AT, false)).isEqualTo(new Verdict(Scope.ADULT, Level.WARNING));
        assertThat(rule.evaluate(p, 120, 120, AT, true)).isEqualTo(new Verdict(Scope.ADULT, Level.URGENT));
    }

    @Test
    @DisplayName("postpartum day 42 is obstetric, day 43 is adult")
    void thePostpartumWindowIs42Days() {
        Patient day42 = adultWoman();
        bornOn(day42, AT.toLocalDate().minusDays(42).atTime(23, 50));
        assertThat(rule.scopeOf(day42, AT)).isEqualTo(Scope.POSTPARTUM);
        assertThat(rule.evaluate(day42, 164, 114, AT, false)).isEqualTo(new Verdict(Scope.POSTPARTUM, Level.URGENT));

        Patient day43 = adultWoman();
        bornOn(day43, AT.toLocalDate().minusDays(43).atTime(0, 10));
        when(histories.findCurrentByPatientId(day43.getId())).thenReturn(Optional.empty());
        assertThat(rule.scopeOf(day43, AT)).isEqualTo(Scope.ADULT);
        assertThat(rule.evaluate(day43, 164, 114, AT, false)).as("adult rule: below 180/120").isEqualTo(Verdict.NONE);
        assertThat(rule.evaluate(day43, 180, 80, AT, false)).isEqualTo(new Verdict(Scope.ADULT, Level.WARNING));
    }

    @Test
    @DisplayName("a pregnant patient (maternal history covering the reading) is under the obstetric rule")
    void aPregnantPatient() {
        Patient p = adultWoman();
        MaternalHistory history = new MaternalHistory();
        history.setEstimatedDueDate(AT.toLocalDate().plusWeeks(10));
        when(histories.findCurrentByPatientId(p.getId())).thenReturn(Optional.of(history));
        bornOn(p, AT.minusYears(3));

        assertThat(rule.scopeOf(p, AT)).isEqualTo(Scope.PREGNANT);
        Verdict v = rule.evaluate(p, 160, 80, AT, false);
        assertThat(v).isEqualTo(new Verdict(Scope.PREGNANT, Level.URGENT));
        assertThat(v.alertCode()).isEqualTo(SevereHypertension.PREGNANCY_ALERT_CODE);
        assertThat(rule.evaluate(p, 120, 110, AT, false).obstetric()).isTrue();
        assertThat(rule.evaluate(p, 159, 109, AT, false)).isEqualTo(Verdict.NONE);
    }

    @Test
    void aPregnancyEndedByABirthIsNotPregnancy() {
        Patient p = adultWoman();
        MaternalHistory history = new MaternalHistory();
        history.setEstimatedDueDate(AT.toLocalDate().minusDays(5));
        when(histories.findCurrentByPatientId(p.getId())).thenReturn(Optional.of(history));
        // Born 60 days ago, within the dated pregnancy: neither pregnant nor postpartum any more.
        bornOn(p, AT.minusDays(60));
        assertThat(rule.isPregnant(p.getId(), AT)).isFalse();
        assertThat(rule.scopeOf(p, AT)).isEqualTo(Scope.ADULT);
    }

    @Test
    void anActiveLabourIsPregnancy() {
        Patient p = adultWoman();
        when(labours.existsByPatient_IdAndStatus(p.getId(), LaborStatus.ACTIVE)).thenReturn(true);
        assertThat(rule.scopeOf(p, AT)).isEqualTo(Scope.PREGNANT);
    }

    @Test
    @DisplayName("under 18: no adult rule; a pregnant 16-year-old is still under the obstetric rule")
    void underEighteen() {
        Patient teen = woman(AT.toLocalDate().minusYears(16));
        when(histories.findCurrentByPatientId(teen.getId())).thenReturn(Optional.empty());
        assertThat(rule.evaluate(teen, 190, 125, AT, true)).isEqualTo(Verdict.NONE);

        Patient pregnantTeen = woman(AT.toLocalDate().minusYears(16));
        when(labours.existsByPatient_IdAndStatus(pregnantTeen.getId(), LaborStatus.ACTIVE)).thenReturn(true);
        assertThat(rule.evaluate(pregnantTeen, 160, 80, AT, false)).isEqualTo(new Verdict(Scope.PREGNANT, Level.URGENT));

        Patient eighteenToday = woman(AT.toLocalDate().minusYears(18));
        when(histories.findCurrentByPatientId(eighteenToday.getId())).thenReturn(Optional.empty());
        assertThat(rule.evaluate(eighteenToday, 180, 80, AT, false).adult()).isTrue();
    }

    @Test
    void aRecordedManIsNeverUnderTheObstetricRule() {
        Patient man = adultWoman();
        man.setGender("M");
        assertThat(rule.evaluate(man, 170, 100, AT, false)).isEqualTo(Verdict.NONE);
        assertThat(rule.evaluate(man, 185, 100, AT, false).adult()).isTrue();
        verifyNoInteractions(deliveries, histories, labours);
    }

    @Test
    void anUnknownBirthDateReadsAsAdult() {
        Patient p = woman(null);
        when(histories.findCurrentByPatientId(p.getId())).thenReturn(Optional.empty());
        assertThat(rule.evaluate(p, 180, 80, AT, false).adult()).isTrue();
    }

    @Test
    void aReadingBelowTheObstetricThresholdsNeverQueriesTheRecord() {
        rule.evaluate(adultWoman(), 159, 109, AT, false);
        verify(deliveries, never()).existsByPatient_IdAndBirthDateTimeBetween(any(), any(), any());
        verifyNoInteractions(histories, labours);
    }

    @Test
    void theMessagesFollowTheScopeAndLevel() {
        assertThat(new Verdict(Scope.POSTPARTUM, Level.URGENT).messageKey()).isEqualTo(SevereHypertension.MESSAGE_KEY);
        assertThat(new Verdict(Scope.PREGNANT, Level.URGENT).messageKey()).isEqualTo(SevereHypertension.PREGNANCY_MESSAGE_KEY);
        assertThat(new Verdict(Scope.ADULT, Level.WARNING).messageKey()).isEqualTo(SevereHypertension.ADULT_MESSAGE_KEY);
        assertThat(new Verdict(Scope.ADULT, Level.URGENT).messageKey()).isEqualTo(SevereHypertension.ADULT_URGENT_MESSAGE_KEY);
    }
}
