package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BpaRuleEngineTest {

    private final PatientVitalSignRepository vitals = mock(PatientVitalSignRepository.class);
    private final PatientProblemRepository problems = mock(PatientProblemRepository.class);
    private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);

    private static Patient patient(UUID id) {
        Patient p = Patient.builder().build();
        p.setId(id);
        return p;
    }

    private static CdsCard card(String summary) {
        return new CdsCard(summary, null, CdsCard.Indicator.WARNING,
            new Source("test", null, null), null, null, null, UUID.randomUUID().toString());
    }

    @Test
    void evaluateNullContextReturnsEmpty() {
        BpaRuleEngine engine = new BpaRuleEngine(List.of(), vitals, problems, prescriptions);
        assertThat(engine.evaluate(null)).isEmpty();
    }

    @Test
    void evaluateContextWithoutPatientReturnsEmpty() {
        BpaRuleEngine engine = new BpaRuleEngine(List.of(), vitals, problems, prescriptions);
        BpaRuleContext ctx = new BpaRuleContext(null, UUID.randomUUID(), null, null, null);
        assertThat(engine.evaluate(ctx)).isEmpty();
    }

    private static BpaRule fixedRule(String id, String summary) {
        return new BpaRule() {
            @Override public String id() { return id; }
            @Override public List<CdsCard> evaluate(BpaRuleContext c) {
                return List.of(card(summary));
            }
        };
    }

    @Test
    void evaluateConcatenatesCardsFromAllRules() {
        BpaRuleEngine engine = new BpaRuleEngine(
            List.of(fixedRule("a", "a"), fixedRule("b", "b")),
            vitals, problems, prescriptions);

        BpaRuleContext ctx = new BpaRuleContext(patient(UUID.randomUUID()),
            UUID.randomUUID(), List.of(), List.of(), List.of());

        assertThat(engine.evaluate(ctx))
            .extracting(CdsCard::summary)
            .containsExactly("a", "b");
    }

    @Test
    void buggyRuleIsIsolatedFromOthers() {
        BpaRule throwing = new BpaRule() {
            @Override public String id() { return "bad"; }
            @Override public List<CdsCard> evaluate(BpaRuleContext c) {
                throw new IllegalStateException("rule explosion");
            }
        };
        BpaRuleEngine engine = new BpaRuleEngine(
            List.of(throwing, fixedRule("ok", "survivor")),
            vitals, problems, prescriptions);
        BpaRuleContext ctx = new BpaRuleContext(patient(UUID.randomUUID()),
            UUID.randomUUID(), List.of(), List.of(), List.of());

        assertThat(engine.evaluate(ctx))
            .extracting(CdsCard::summary)
            .containsExactly("survivor");
    }

    @Test
    void buildContextWithNullPatientReturnsEmptyContext() {
        BpaRuleEngine engine = new BpaRuleEngine(List.of(), vitals, problems, prescriptions);
        BpaRuleContext ctx = engine.buildContext(null, UUID.randomUUID());
        assertThat(ctx.hasPatient()).isFalse();
        assertThat(ctx.recentVitals()).isEmpty();
        assertThat(ctx.activeProblems()).isEmpty();
        assertThat(ctx.activePrescriptions()).isEmpty();
    }

    @Test
    void buildContextLoadsScopedRecentVitalsAndFiltersTerminalPrescriptions() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = patient(patientId);

        PatientVitalSign recent = PatientVitalSign.builder()
            .recordedAt(LocalDateTime.now().minusMinutes(30))
            .temperatureCelsius(37.0)
            .build();
        when(vitals.findWithinRange(eq(patientId), eq(hospitalId), any(), any(), any()))
            .thenReturn(List.of(recent));

        PatientProblem activeProblem = PatientProblem.builder().status(ProblemStatus.ACTIVE).build();
        PatientProblem resolved = PatientProblem.builder().status(ProblemStatus.RESOLVED).build();
        when(problems.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(activeProblem, resolved));

        Prescription signed = Prescription.builder().status(PrescriptionStatus.SIGNED).build();
        Prescription cancelled = Prescription.builder().status(PrescriptionStatus.CANCELLED).build();
        when(prescriptions.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(signed, cancelled));

        BpaRuleEngine engine = new BpaRuleEngine(List.of(), vitals, problems, prescriptions);
        BpaRuleContext ctx = engine.buildContext(patient, hospitalId);

        assertThat(ctx.hasPatient()).isTrue();
        assertThat(ctx.recentVitals()).containsExactly(recent);
        assertThat(ctx.activeProblems()).containsExactly(activeProblem);
        assertThat(ctx.activePrescriptions()).containsExactly(signed);
    }

    @Test
    void buildContextFallsBackToUnscopedRepoWhenHospitalIdIsNull() {
        UUID patientId = UUID.randomUUID();
        Patient patient = patient(patientId);

        when(vitals.findWithinRange(eq(patientId), eq(null), any(), any(), any()))
            .thenReturn(List.of());
        when(problems.findByPatient_Id(patientId)).thenReturn(List.of());

        Page<Prescription> empty = new PageImpl<>(List.of());
        when(prescriptions.findByPatient_Id(eq(patientId), any(Pageable.class))).thenReturn(empty);

        BpaRuleEngine engine = new BpaRuleEngine(List.of(), vitals, problems, prescriptions);
        BpaRuleContext ctx = engine.buildContext(patient, null);

        assertThat(ctx.hasPatient()).isTrue();
        assertThat(ctx.activeProblems()).isEmpty();
    }
}
