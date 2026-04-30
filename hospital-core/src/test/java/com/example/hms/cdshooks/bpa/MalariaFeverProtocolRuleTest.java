package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.repository.BpaProtocolRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MalariaFeverProtocolRuleTest {

    private final BpaProtocolRepository repo = mock(BpaProtocolRepository.class);
    private final MalariaFeverProtocolRule rule = new MalariaFeverProtocolRule(repo);

    private static Patient patient() {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private static PatientVitalSign vitals(double tempC, LocalDateTime when) {
        return PatientVitalSign.builder()
            .temperatureCelsius(tempC)
            .recordedAt(when)
            .build();
    }

    private static PatientProblem problem(String code, String display) {
        return PatientProblem.builder().problemCode(code).problemDisplay(display).build();
    }

    private static Prescription prescription(String name) {
        return Prescription.builder().medicationName(name).build();
    }

    private static BpaProtocol activeProtocol() {
        return BpaProtocol.builder()
            .protocolCode("MALARIA_FEVER")
            .name("Malaria — febrile patient workup")
            .summary("Order rapid diagnostic test...")
            .protocolUrl("https://example.org/who")
            .active(true)
            .build();
    }

    private static BpaRuleContext context(List<PatientVitalSign> vitals,
                                          List<PatientProblem> problems,
                                          List<Prescription> prescriptions) {
        return new BpaRuleContext(patient(), UUID.randomUUID(),
            vitals, problems, prescriptions);
    }

    @Test
    void firesWhenRecentFeverAndNoActiveTreatment() {
        when(repo.findByProtocolCodeAndActiveTrue("MALARIA_FEVER"))
            .thenReturn(Optional.of(activeProtocol()));

        BpaRuleContext ctx = context(
            List.of(vitals(39.2, LocalDateTime.now().minusHours(3))),
            List.of(),
            List.of()
        );

        List<CdsCard> cards = rule.evaluate(ctx);
        assertThat(cards).hasSize(1);
        CdsCard card = cards.get(0);
        assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.WARNING);
        assertThat(card.summary()).contains("Malaria");
        assertThat(card.detail()).contains("39.2");
        assertThat(card.links()).hasSize(1);
    }

    @Test
    void doesNotFireBelowFeverThreshold() {
        BpaRuleContext ctx = context(
            List.of(vitals(38.4, LocalDateTime.now().minusHours(1))),
            List.of(),
            List.of()
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void doesNotFireWhenFeverIsOlderThan24h() {
        BpaRuleContext ctx = context(
            List.of(vitals(39.5, LocalDateTime.now().minusHours(30))),
            List.of(),
            List.of()
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void doesNotFireWhenActiveMalariaProblemPresent() {
        when(repo.findByProtocolCodeAndActiveTrue("MALARIA_FEVER"))
            .thenReturn(Optional.of(activeProtocol()));

        BpaRuleContext ctx = context(
            List.of(vitals(39.0, LocalDateTime.now().minusHours(2))),
            List.of(problem("B54", "Unspecified malaria")),
            List.of()
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void doesNotFireWhenActiveAntimalarialPrescriptionPresent() {
        when(repo.findByProtocolCodeAndActiveTrue("MALARIA_FEVER"))
            .thenReturn(Optional.of(activeProtocol()));

        BpaRuleContext ctx = context(
            List.of(vitals(39.0, LocalDateTime.now().minusHours(2))),
            List.of(),
            List.of(prescription("Artemether/Lumefantrine 80/480 mg"))
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void doesNotFireWhenProtocolRowIsAbsent() {
        when(repo.findByProtocolCodeAndActiveTrue("MALARIA_FEVER")).thenReturn(Optional.empty());
        BpaRuleContext ctx = context(
            List.of(vitals(39.5, LocalDateTime.now().minusHours(1))),
            List.of(),
            List.of()
        );
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void evaluateReturnsEmptyWhenContextIsNullOrPatientless() {
        assertThat(rule.evaluate(null)).isEmpty();
        BpaRuleContext empty = new BpaRuleContext(null, UUID.randomUUID(),
            List.of(), List.of(), List.of());
        assertThat(rule.evaluate(empty)).isEmpty();
    }
}
