package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.BpaProtocolRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObHemorrhageProtocolRuleTest {

    private final BpaProtocolRepository repo = mock(BpaProtocolRepository.class);
    private final ObHemorrhageProtocolRule rule = new ObHemorrhageProtocolRule(repo);

    private static Patient patient() {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private static PatientVitalSign vitals(Integer hr, Integer sbp, LocalDateTime when) {
        return PatientVitalSign.builder()
            .heartRateBpm(hr)
            .systolicBpMmHg(sbp)
            .recordedAt(when)
            .build();
    }

    private static PatientProblem postpartumProblem() {
        return PatientProblem.builder()
            .problemCode("Z39.0")
            .problemDisplay("Encounter for postpartum care and examination")
            .build();
    }

    private static BpaProtocol activeProtocol() {
        return BpaProtocol.builder()
            .protocolCode("OB_HEMORRHAGE")
            .name("Postpartum hemorrhage")
            .summary("Activate PPH bundle...")
            .protocolUrl("https://example.org/figo")
            .active(true)
            .build();
    }

    private static BpaRuleContext context(List<PatientVitalSign> vitals,
                                          List<PatientProblem> problems) {
        return new BpaRuleContext(patient(), UUID.randomUUID(),
            vitals, problems, List.of());
    }

    @Test
    void firesWhenPostpartumAndTachycardicInWindow() {
        when(repo.findByProtocolCodeAndActiveTrue("OB_HEMORRHAGE"))
            .thenReturn(Optional.of(activeProtocol()));

        BpaRuleContext ctx = context(
            List.of(vitals(118, 110, LocalDateTime.now().minusHours(1))),
            List.of(postpartumProblem())
        );

        List<CdsCard> cards = rule.evaluate(ctx);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
        assertThat(cards.get(0).detail()).contains("Tachycardia");
    }

    @Test
    void firesWhenPostpartumAndHypotensiveInWindow() {
        when(repo.findByProtocolCodeAndActiveTrue("OB_HEMORRHAGE"))
            .thenReturn(Optional.of(activeProtocol()));

        BpaRuleContext ctx = context(
            List.of(vitals(95, 85, LocalDateTime.now().minusMinutes(20))),
            List.of(postpartumProblem())
        );
        assertThat(rule.evaluate(ctx)).hasSize(1);
    }

    @Test
    void doesNotFireWithoutPostpartumProblem() {
        BpaRuleContext ctx = context(
            List.of(vitals(120, 80, LocalDateTime.now().minusMinutes(10))),
            List.of(PatientProblem.builder().problemDisplay("Hypertension").build())
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void doesNotFireWhenVitalsAreOutsideWindow() {
        when(repo.findByProtocolCodeAndActiveTrue("OB_HEMORRHAGE"))
            .thenReturn(Optional.of(activeProtocol()));

        BpaRuleContext ctx = context(
            List.of(vitals(115, 85, LocalDateTime.now().minusHours(7))),
            List.of(postpartumProblem())
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void doesNotFireWhenVitalsAreReassuring() {
        BpaRuleContext ctx = context(
            List.of(vitals(78, 118, LocalDateTime.now().minusMinutes(15))),
            List.of(postpartumProblem())
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void doesNotFireWhenProtocolRowIsAbsent() {
        when(repo.findByProtocolCodeAndActiveTrue("OB_HEMORRHAGE")).thenReturn(Optional.empty());
        BpaRuleContext ctx = context(
            List.of(vitals(115, 85, LocalDateTime.now().minusMinutes(10))),
            List.of(postpartumProblem())
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void evaluateReturnsEmptyOnNullOrPatientlessContext() {
        assertThat(rule.evaluate(null)).isEmpty();
        BpaRuleContext empty = new BpaRuleContext(null, UUID.randomUUID(),
            List.of(), List.of(), List.of());
        assertThat(rule.evaluate(empty)).isEmpty();
    }
}
