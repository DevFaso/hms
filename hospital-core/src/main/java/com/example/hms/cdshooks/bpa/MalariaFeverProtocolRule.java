package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsLink;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.repository.BpaProtocolRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Best-Practice Advisory: febrile patient without an active malaria
 * workup. Fires when the most recent recorded temperature in the last
 * 24h is ≥ 38.5°C AND the patient has no active malaria problem AND no
 * active anti-malarial prescription.
 *
 * <p>Why ≥ 38.5°C: WHO febrile-illness threshold for empirical malaria
 * testing in endemic areas — well above ambient/measurement-noise but
 * below frank hyperpyrexia, the band where a clinician most benefits
 * from a nudge.
 *
 * <p>This rule is the first BPA shipped under {@code hms-bpa-protocols}.
 * It is advisory-only — never blocks the chart load.
 */
@Service
public class MalariaFeverProtocolRule implements BpaRule {

    public static final String PROTOCOL_CODE = "MALARIA_FEVER";
    private static final Duration LOOKBACK = Duration.ofHours(24);
    private static final double FEVER_THRESHOLD_C = 38.5d;

    /**
     * Heuristic substrings (case-insensitive) used to detect existing
     * malaria diagnoses or anti-malarial therapy on the chart. Kept here
     * rather than in V64 because they are rule logic, not catalog data.
     * ICD-10 B50–B54 covers human Plasmodium spp.
     */
    private static final List<String> MALARIA_PROBLEM_HINTS = List.of(
        "malaria", "plasmodium", "b50", "b51", "b52", "b53", "b54"
    );
    private static final List<String> ANTIMALARIAL_HINTS = List.of(
        "artemether", "lumefantrine", "artesunate", "amodiaquine",
        "quinine", "mefloquine", "chloroquine", "primaquine", "doxycycline"
    );

    private final BpaProtocolRepository protocolRepository;

    public MalariaFeverProtocolRule(BpaProtocolRepository protocolRepository) {
        this.protocolRepository = protocolRepository;
    }

    @Override
    public String id() {
        return PROTOCOL_CODE;
    }

    @Override
    public List<CdsCard> evaluate(BpaRuleContext context) {
        if (context == null || !context.hasPatient()) return List.of();

        Optional<PatientVitalSign> feverEvent = findFeverInWindow(context.recentVitals());
        if (feverEvent.isEmpty()) return List.of();

        if (hasActiveMalariaProblem(context.activeProblems())) return List.of();
        if (hasActiveAntimalarial(context.activePrescriptions())) return List.of();

        Optional<BpaProtocol> protocol = protocolRepository.findByProtocolCodeAndActiveTrue(PROTOCOL_CODE);
        if (protocol.isEmpty()) return List.of();

        return List.of(buildCard(protocol.get(), feverEvent.get()));
    }

    Optional<PatientVitalSign> findFeverInWindow(List<PatientVitalSign> vitals) {
        LocalDateTime cutoff = LocalDateTime.now().minus(LOOKBACK);
        return vitals.stream()
            .filter(v -> v.getTemperatureCelsius() != null)
            .filter(v -> v.getRecordedAt() != null && !v.getRecordedAt().isBefore(cutoff))
            .filter(v -> v.getTemperatureCelsius() >= FEVER_THRESHOLD_C)
            .findFirst();
    }

    boolean hasActiveMalariaProblem(List<PatientProblem> problems) {
        return problems.stream().anyMatch(p ->
            matchesAny(p.getProblemCode(), MALARIA_PROBLEM_HINTS)
                || matchesAny(p.getProblemDisplay(), MALARIA_PROBLEM_HINTS));
    }

    boolean hasActiveAntimalarial(List<Prescription> prescriptions) {
        return prescriptions.stream().anyMatch(p ->
            matchesAny(p.getMedicationName(), ANTIMALARIAL_HINTS));
    }

    private static boolean matchesAny(String value, List<String> hints) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return hints.stream().anyMatch(lower::contains);
    }

    private static CdsCard buildCard(BpaProtocol protocol, PatientVitalSign feverEvent) {
        String detail = protocol.getSummary()
            + "\n\nMost recent temperature: "
            + String.format(Locale.ROOT, "%.1f", feverEvent.getTemperatureCelsius())
            + "°C at "
            + feverEvent.getRecordedAt() + ".";
        List<CdsLink> links = protocol.getProtocolUrl() == null
            ? null
            : List.of(new CdsLink(
                "WHO malaria guideline",
                protocol.getProtocolUrl(),
                "absolute",
                null));
        return new CdsCard(
            protocol.getName(),
            detail,
            CdsCard.Indicator.WARNING,
            new Source("HMS Best-Practice Advisory", null, null),
            links,
            null,
            null,
            java.util.UUID.randomUUID().toString()
        );
    }
}
