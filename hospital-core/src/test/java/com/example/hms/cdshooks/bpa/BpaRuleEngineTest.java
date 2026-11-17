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

    /**
     * QA D1194 (round 22): a card's id is its rule and level - the same on
     * every evaluation and in every language - so the 24-hour acknowledgement
     * never falls back to the card's text. Every rule goes through the engine.
     */
    @Test
    void everyCardGetsAnIdFromItsRuleAndLevelNotARandomOne_D1194() {
        BpaRule rule = new BpaRule() {
            @Override public String id() { return "pregnancy-htn"; }
            @Override public List<CdsCard> evaluate(BpaRuleContext context) {
                return List.of(card("Hypertension"));
            }
        };
        BpaRuleEngine engine = new BpaRuleEngine(List.of(rule), vitals, problems, prescriptions);
        BpaRuleContext ctx = new BpaRuleContext(patient(UUID.randomUUID()), UUID.randomUUID(), null, null, null);

        String first = engine.evaluate(ctx).get(0).uuid();
        String second = engine.evaluate(ctx).get(0).uuid();
        assertThat(first).isEqualTo(second).isEqualTo(BpaRuleEngine.stableCardUuid("pregnancy-htn", card("x")));
        CdsCard critical = new CdsCard("x", null, CdsCard.Indicator.CRITICAL, new Source("t", null, null),
            null, null, null, null);
        assertThat(BpaRuleEngine.stableCardUuid("pregnancy-htn", critical))
            .as("a warning acknowledged does not hide the critical card").isNotEqualTo(first);
    }

    /** The guard: no BPA rule mints a random card id (the engine's id is the only one). */
    @Test
    void noBpaRuleMintsARandomCardId_D1194() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("src/main/java/com/example/hms/cdshooks/bpa");
        java.util.List<String> offenders = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (java.nio.file.Files.readString(f).contains("randomUUID")) {
                    offenders.add(f.getFileName().toString());
                }
            }
        }
        assertThat(offenders).as("a BPA card id must be stable (QA D1194)").isEmpty();
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
