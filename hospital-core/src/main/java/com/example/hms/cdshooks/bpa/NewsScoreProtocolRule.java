package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsLink;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.BpaProtocolRepository;
import com.example.hms.utility.NewsScoreCalculator;
import com.example.hms.utility.NewsScoreCalculator.NewsScoreResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Best-Practice Advisory: NEWS2 early-warning score (P3 #25b). Scores
 * the most recent clinically-recorded vitals bundle within the last 12
 * hours and raises a card at aggregate MEDIUM (≥5) or when any single
 * parameter scores 3 — the RCP escalation thresholds.
 *
 * <p>Two deliberate stances:
 * <ul>
 *   <li><strong>Partial scores are shown, marked incomplete.</strong>
 *       Refusing to score when O₂ or consciousness was not recorded
 *       would hide what IS known; scoring silently would under-score
 *       exactly the deteriorating patients the score exists to catch.
 *       The card names every missing parameter.</li>
 *   <li><strong>Home readings are excluded.</strong> PATIENT_REPORTED
 *       vitals lack the clinical-assessment parameters and mix
 *       measurement contexts; NEWS2 is defined over clinical
 *       observations.</li>
 * </ul>
 *
 * <p>Advisory only — the BPA contract forbids CRITICAL cards. A HIGH
 * (≥7) band renders as the strongest wording this surface allows; the
 * write-path auto-flag ({@code clinicallySignificant}) is what feeds
 * the nurse-facing worklists.
 */
@Service
public class NewsScoreProtocolRule implements BpaRule {

    public static final String PROTOCOL_CODE = "NEWS2_EWS";
    private static final Duration LOOKBACK = Duration.ofHours(12);
    private static final String PATIENT_REPORTED_SOURCE = "PATIENT_REPORTED";

    private final BpaProtocolRepository protocolRepository;

    public NewsScoreProtocolRule(BpaProtocolRepository protocolRepository) {
        this.protocolRepository = protocolRepository;
    }

    @Override
    public String id() {
        return PROTOCOL_CODE;
    }

    @Override
    public List<CdsCard> evaluate(BpaRuleContext context) {
        if (context == null || !context.hasPatient()) return List.of();

        Optional<PatientVitalSign> latest = latestClinicalVitals(context.recentVitals());
        if (latest.isEmpty()) return List.of();

        NewsScoreResult score = NewsScoreCalculator.score(latest.get());
        if (score.riskBand() == NewsScoreCalculator.RiskBand.LOW) return List.of();

        Optional<BpaProtocol> protocol = protocolRepository.findByProtocolCodeAndActiveTrue(PROTOCOL_CODE);
        if (protocol.isEmpty()) return List.of();

        return List.of(buildCard(protocol.get(), score, latest.get()));
    }

    /** Most recent non-home bundle inside the lookback (vitals arrive DESC). */
    private static Optional<PatientVitalSign> latestClinicalVitals(List<PatientVitalSign> vitals) {
        LocalDateTime cutoff = LocalDateTime.now().minus(LOOKBACK);
        return vitals.stream()
            .filter(v -> v.getRecordedAt() != null && !v.getRecordedAt().isBefore(cutoff))
            .filter(v -> !PATIENT_REPORTED_SOURCE.equalsIgnoreCase(v.getSource()))
            .findFirst();
    }

    private static CdsCard buildCard(BpaProtocol protocol, NewsScoreResult score,
                                     PatientVitalSign vitals) {
        StringBuilder detail = new StringBuilder(protocol.getSummary())
            .append("\n\nNEWS2: ").append(score.total())
            .append(" (").append(score.riskBand().name().replace('_', '-')).append(" risk)")
            .append(" — vitals recorded ").append(vitals.getRecordedAt());
        if (!score.complete()) {
            detail.append("\n⚠ Score is INCOMPLETE — not recorded: ")
                .append(String.join(", ", score.missingParameters()))
                .append(". The true score can only be equal or higher.");
        }
        if (score.anyParameterThree()) {
            detail.append("\nAt least one parameter scores 3 — urgent review per policy.");
        }
        List<CdsLink> links = protocol.getProtocolUrl() == null
            ? null
            : List.of(new CdsLink(
                "RCP — NEWS2 and escalation matrix",
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
}
