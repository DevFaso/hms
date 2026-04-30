package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsLink;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.BpaProtocolRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Best-Practice Advisory: postpartum hemorrhage screening. Fires when
 * the patient has an active postpartum-related problem AND the most
 * recent vitals in the last 6 hours show either:
 * <ul>
 *   <li>Heart rate &gt; 100 bpm (tachycardia from blood loss), or</li>
 *   <li>Systolic blood pressure &lt; 90 mmHg (hypotension).</li>
 * </ul>
 *
 * <p>Why these thresholds: the FIGO PPH initiative cites HR &gt; 100
 * and sBP &lt; 90 as the bedside red-flags that should escalate to PPH
 * bundle activation. Using the active problem list as the postpartum
 * gate (rather than encounter type) lets the rule fire for women still
 * registered as inpatient hours-to-days after delivery.
 *
 * <p>Advisory only — never blocks the chart load.
 */
@Service
public class ObHemorrhageProtocolRule implements BpaRule {

    public static final String PROTOCOL_CODE = "OB_HEMORRHAGE";
    private static final Duration LOOKBACK = Duration.ofHours(6);

    private static final int HR_THRESHOLD = 100;
    private static final int SBP_THRESHOLD = 90;

    /**
     * Heuristic substrings (case-insensitive) used to detect a postpartum
     * state on the active problem list. ICD-10: O72 = postpartum
     * hemorrhage, O85 = puerperal sepsis, Z39 = encounter for postpartum
     * care. Display strings catch sites that haven't fully bound to ICD.
     */
    private static final List<String> POSTPARTUM_HINTS = List.of(
        "postpartum", "post-partum", "post partum", "puerperium",
        "puerperal", "o72", "o73", "o85", "z39"
    );

    private final BpaProtocolRepository protocolRepository;

    public ObHemorrhageProtocolRule(BpaProtocolRepository protocolRepository) {
        this.protocolRepository = protocolRepository;
    }

    @Override
    public String id() {
        return PROTOCOL_CODE;
    }

    @Override
    public List<CdsCard> evaluate(BpaRuleContext context) {
        if (context == null || !context.hasPatient()) return List.of();

        if (!isPostpartum(context.activeProblems())) return List.of();

        Optional<HemorrhageSignal> signal = detectSignal(context.recentVitals());
        if (signal.isEmpty()) return List.of();

        Optional<BpaProtocol> protocol = protocolRepository.findByProtocolCodeAndActiveTrue(PROTOCOL_CODE);
        if (protocol.isEmpty()) return List.of();

        return List.of(buildCard(protocol.get(), signal.get()));
    }

    boolean isPostpartum(List<PatientProblem> problems) {
        return problems.stream().anyMatch(p ->
            matchesAny(p.getProblemCode(), POSTPARTUM_HINTS)
                || matchesAny(p.getProblemDisplay(), POSTPARTUM_HINTS));
    }

    Optional<HemorrhageSignal> detectSignal(List<PatientVitalSign> vitals) {
        LocalDateTime cutoff = LocalDateTime.now().minus(LOOKBACK);
        for (PatientVitalSign v : vitals) {
            if (v.getRecordedAt() == null || v.getRecordedAt().isBefore(cutoff)) continue;
            boolean tachy = v.getHeartRateBpm() != null && v.getHeartRateBpm() > HR_THRESHOLD;
            boolean hypo = v.getSystolicBpMmHg() != null && v.getSystolicBpMmHg() < SBP_THRESHOLD;
            if (tachy || hypo) {
                return Optional.of(new HemorrhageSignal(
                    v.getHeartRateBpm(),
                    v.getSystolicBpMmHg(),
                    v.getRecordedAt(),
                    tachy,
                    hypo
                ));
            }
        }
        return Optional.empty();
    }

    private static boolean matchesAny(String value, List<String> hints) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return hints.stream().anyMatch(lower::contains);
    }

    private static CdsCard buildCard(BpaProtocol protocol, HemorrhageSignal signal) {
        StringBuilder detail = new StringBuilder(protocol.getSummary());
        detail.append("\n\nMost recent triggering vitals at ")
            .append(signal.recordedAt())
            .append(": HR=")
            .append(signal.hr() == null ? "—" : signal.hr())
            .append(" bpm, sBP=")
            .append(signal.sbp() == null ? "—" : signal.sbp())
            .append(" mmHg.");
        if (signal.tachy()) detail.append(" Tachycardia.");
        if (signal.hypo()) detail.append(" Hypotension.");
        List<CdsLink> links = protocol.getProtocolUrl() == null
            ? null
            : List.of(new CdsLink(
                "FIGO PPH initiative",
                protocol.getProtocolUrl(),
                "absolute",
                null));
        return new CdsCard(
            protocol.getName(),
            detail.toString(),
            CdsCard.Indicator.WARNING,
            new Source("HMS Best-Practice Advisory", null, null),
            links,
            null,
            null,
            java.util.UUID.randomUUID().toString()
        );
    }

    /** Internal record for the most recent triggering vitals snapshot. */
    record HemorrhageSignal(
        Integer hr,
        Integer sbp,
        LocalDateTime recordedAt,
        boolean tachy,
        boolean hypo
    ) {}
}
