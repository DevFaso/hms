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

class SepsisQsofaProtocolRuleTest {

    private final BpaProtocolRepository repo = mock(BpaProtocolRepository.class);
    private final SepsisQsofaProtocolRule rule = new SepsisQsofaProtocolRule(repo);

    private static Patient patient() {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private static PatientVitalSign vitals(Integer rr, Integer sbp, LocalDateTime when) {
        return PatientVitalSign.builder()
            .respiratoryRateBpm(rr)
            .systolicBpMmHg(sbp)
            .recordedAt(when)
            .build();
    }

    private static PatientProblem problem(String display) {
        return PatientProblem.builder().problemDisplay(display).build();
    }

    private static BpaProtocol activeProtocol() {
        return BpaProtocol.builder()
            .protocolCode("SEPSIS_QSOFA")
            .name("Sepsis — Hour-1 bundle")
            .summary("Draw cultures + lactate...")
            .protocolUrl("https://example.org/sccm")
            .active(true)
            .build();
    }

    private static BpaRuleContext context(List<PatientVitalSign> vitals,
                                          List<PatientProblem> problems) {
        return new BpaRuleContext(patient(), UUID.randomUUID(),
            vitals, problems, List.of());
    }

    @Test
    void firesWhenRrAndSbpBothMeetCriteriaInWindow() {
        when(repo.findByProtocolCodeAndActiveTrue("SEPSIS_QSOFA"))
            .thenReturn(Optional.of(activeProtocol()));

        BpaRuleContext ctx = context(
            List.of(vitals(24, 95, LocalDateTime.now().minusHours(2))),
            List.of()
        );
        List<CdsCard> cards = rule.evaluate(ctx);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
        assertThat(cards.get(0).detail()).contains("2/3");
    }

    @Test
    void firesWhenRrPlusAmsCombineToTwo() {
        when(repo.findByProtocolCodeAndActiveTrue("SEPSIS_QSOFA"))
            .thenReturn(Optional.of(activeProtocol()));

        BpaRuleContext ctx = context(
            List.of(vitals(24, 130, LocalDateTime.now().minusHours(1))),
            List.of(problem("Altered mental status — confused on arrival"))
        );
        assertThat(rule.evaluate(ctx)).hasSize(1);
    }

    @Test
    void doesNotFireWithOnlyOneCriterion() {
        BpaRuleContext ctx = context(
            List.of(vitals(24, 130, LocalDateTime.now().minusHours(1))),
            List.of()
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void doesNotFireWhenCriteriaAreOlderThanWindow() {
        BpaRuleContext ctx = context(
            List.of(vitals(28, 90, LocalDateTime.now().minusHours(7))),
            List.of()
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void firesAcrossMultipleVitalsRecordsCombiningCriteria() {
        when(repo.findByProtocolCodeAndActiveTrue("SEPSIS_QSOFA"))
            .thenReturn(Optional.of(activeProtocol()));

        BpaRuleContext ctx = context(
            List.of(
                vitals(28, 130, LocalDateTime.now().minusHours(2)),
                vitals(18, 95, LocalDateTime.now().minusHours(4))
            ),
            List.of()
        );
        // RR=28 hits ≥22 in record 1; sBP=95 hits ≤100 in record 2; both in 6h window.
        assertThat(rule.evaluate(ctx)).hasSize(1);
    }

    @Test
    void doesNotFireWhenProtocolRowIsAbsent() {
        when(repo.findByProtocolCodeAndActiveTrue("SEPSIS_QSOFA")).thenReturn(Optional.empty());
        BpaRuleContext ctx = context(
            List.of(vitals(28, 90, LocalDateTime.now().minusHours(1))),
            List.of()
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
