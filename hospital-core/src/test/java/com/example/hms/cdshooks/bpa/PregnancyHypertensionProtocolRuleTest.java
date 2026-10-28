package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.BpaProtocolRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** QA D971: 152/102 at 22 weeks raised no alert outside a labour episode. */
class PregnancyHypertensionProtocolRuleTest {

    private final BpaProtocolRepository repo = mock(BpaProtocolRepository.class);
    private final PregnancyHypertensionProtocolRule rule = new PregnancyHypertensionProtocolRule(repo);

    /** A due date that puts the patient at the given week of pregnancy today. */
    private static LocalDate dueDateAtWeek(int weeks) {
        return LocalDate.now().minusWeeks(weeks).plusDays(PregnancyHypertensionProtocolRule.PREGNANCY_DAYS);
    }

    private static PatientVitalSign bp(Integer sys, Integer dia) {
        return PatientVitalSign.builder().systolicBpMmHg(sys).diastolicBpMmHg(dia)
            .recordedAt(LocalDateTime.now().minusMinutes(30)).build();
    }

    private static BpaRuleContext context(LocalDate dueDate, PatientVitalSign... vitals) {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        return new BpaRuleContext(p, UUID.randomUUID(), List.of(vitals), List.of(), List.of(), dueDate);
    }

    @Test
    void qaD971_hypertensionAt22WeeksRaisesAWarningCard() {
        when(repo.findByProtocolCode("PREGNANCY_HYPERTENSION")).thenReturn(Optional.empty());
        List<CdsCard> cards = rule.evaluate(context(dueDateAtWeek(22), bp(152, 102)));
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
        assertThat(cards.get(0).detail()).contains("152/102").contains("22 weeks");
    }

    @Test
    void theSevereRangeIsCritical() {
        when(repo.findByProtocolCode("PREGNANCY_HYPERTENSION")).thenReturn(Optional.empty());
        assertThat(rule.evaluate(context(dueDateAtWeek(30), bp(165, 100))).get(0).indicator())
            .isEqualTo(CdsCard.Indicator.CRITICAL);
        assertThat(rule.evaluate(context(dueDateAtWeek(30), bp(130, 110))).get(0).indicator())
            .isEqualTo(CdsCard.Indicator.CRITICAL);
    }

    @Test
    void theCutoffsAreInclusive() {
        when(repo.findByProtocolCode("PREGNANCY_HYPERTENSION")).thenReturn(Optional.empty());
        assertThat(rule.evaluate(context(dueDateAtWeek(25), bp(140, 80)))).hasSize(1);
        assertThat(rule.evaluate(context(dueDateAtWeek(25), bp(120, 90)))).hasSize(1);
        assertThat(rule.evaluate(context(dueDateAtWeek(25), bp(139, 89)))).isEmpty();
    }

    @Test
    void noPregnancyNoCard() {
        assertThat(rule.evaluate(context(null, bp(170, 115)))).isEmpty();
        // Delivered long ago: due date more than two weeks past.
        assertThat(rule.evaluate(context(LocalDate.now().minusWeeks(6), bp(170, 115)))).isEmpty();
    }

    @Test
    void anAdministratorCanSwitchTheRuleOff() {
        BpaProtocol off = BpaProtocol.builder().protocolCode("PREGNANCY_HYPERTENSION").active(false).build();
        when(repo.findByProtocolCode("PREGNANCY_HYPERTENSION")).thenReturn(Optional.of(off));
        assertThat(rule.evaluate(context(dueDateAtWeek(22), bp(152, 102)))).isEmpty();
    }

    @Test
    void aBirthRecordedSinceThePregnancyBeganEndsIt() {
        com.example.hms.repository.MaternalHistoryRepository histories =
            mock(com.example.hms.repository.MaternalHistoryRepository.class);
        com.example.hms.repository.DeliveryRecordRepository deliveries =
            mock(com.example.hms.repository.DeliveryRecordRepository.class);
        BpaRuleEngine engine = new BpaRuleEngine(List.of(), null, null, null, histories, deliveries);
        UUID patientId = UUID.randomUUID();
        MaternalHistory h = new MaternalHistory();
        h.setEstimatedDueDate(dueDateAtWeek(38));
        when(histories.findCurrentByPatientId(patientId)).thenReturn(Optional.of(h));

        assertThat(engine.loadEstimatedDueDate(patientId, null)).isEqualTo(h.getEstimatedDueDate());
        when(deliveries.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(org.mockito.ArgumentMatchers.eq(patientId),
            org.mockito.ArgumentMatchers.any())).thenReturn(true);
        assertThat(engine.loadEstimatedDueDate(patientId, null)).isNull();
    }

    @Test
    void theEngineReadsTheDueDateUltrasoundFirstThenRecordedThenLmp() {
        MaternalHistory h = new MaternalHistory();
        h.setLastMenstrualPeriod(LocalDate.of(2026, 5, 20));
        assertThat(BpaRuleEngine.dueDateOf(h)).isEqualTo(LocalDate.of(2027, 2, 24));
        h.setEstimatedDueDate(LocalDate.of(2027, 2, 20));
        assertThat(BpaRuleEngine.dueDateOf(h)).isEqualTo(LocalDate.of(2027, 2, 20));
        h.setEstimatedDueDateByUltrasound(LocalDate.of(2027, 3, 1));
        assertThat(BpaRuleEngine.dueDateOf(h)).isEqualTo(LocalDate.of(2027, 3, 1));
    }
}
