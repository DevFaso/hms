package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsLink;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.BpaProtocolRepository;
import com.example.hms.service.vitals.News2Applicability;
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
    private final CdsCardText text;

    /** English-only, for rules built outside Spring. */
    public NewsScoreProtocolRule(BpaProtocolRepository protocolRepository) {
        this(protocolRepository, CdsCardText.ENGLISH);
    }

    /** QA D217: the card is written in the caller's language, like the D61 rules. */
    @org.springframework.beans.factory.annotation.Autowired
    public NewsScoreProtocolRule(BpaProtocolRepository protocolRepository, CdsCardText text) {
        this.protocolRepository = protocolRepository;
        this.text = text;
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
        // QA D518: no adult NEWS2 card for a child. QA D1121: asked of the
        // context's patient only - the bundle's own patient is a LAZY proxy
        // (the same patient: vitals are loaded by patient id) that cannot be
        // read outside a session, and reading it dropped every NEWS2 card.
        NewsScoreResult score = NewsScoreCalculator.score(latest.get());
        // Review of #1237 (High): in pregnancy, after a birth and for a newborn NEWS2 is not validated. The card
        // never shows a (possibly reassuring) score; it says so and points to the maternity alerts. A raised
        // band still makes it a warning: the trigger stays as a safety net.
        News2Applicability.Status status = applicability == null ? News2Applicability.Status.APPLIES
            : applicability.statusOf(context.patient(), context.hospitalId(), latest.get().getRecordedAt());
        if (status.notValidated()) {
            Optional<BpaProtocol> protocol = protocolRepository.findByProtocolCodeAndActiveTrue(PROTOCOL_CODE);
            return protocol.isEmpty() ? List.of() : List.of(notValidatedCard(protocol.get(), status,
                BpaCardText.when(text, latest.get().getRecordedAt(), text.zoneOf(context.hospitalId())),
                // The adult bands mean nothing for a newborn (her own rules alert): never a warning from them.
                status == News2Applicability.Status.OBSTETRIC && score.riskBand() != NewsScoreCalculator.RiskBand.LOW));
        }
        if (!NewsScoreCalculator.appliesTo(context.patient())) {
            return List.of();
        }
        if (score.riskBand() == NewsScoreCalculator.RiskBand.LOW) return List.of();

        Optional<BpaProtocol> protocol = protocolRepository.findByProtocolCodeAndActiveTrue(PROTOCOL_CODE);
        if (protocol.isEmpty()) return List.of();

        return List.of(buildCard(protocol.get(), score, latest.get(), text.zoneOf(context.hospitalId())));
    }

    /** Review of #1237: the one NEWS2 applicability rule (setter-injected; absent, the card is as before). */
    private News2Applicability applicability;

    @org.springframework.beans.factory.annotation.Autowired
    void setApplicability(News2Applicability applicability) {
        this.applicability = applicability;
    }

    /** « NEWS2 non validé … — voir les alertes maternité », with no score. */
    private CdsCard notValidatedCard(BpaProtocol protocol, News2Applicability.Status status, String readAt,
                                     boolean raised) {
        String key = status == News2Applicability.Status.NEWBORN
            ? "cds.bpa.news2.notValidated.newborn" : "cds.bpa.news2.notValidated.obstetric";
        String fallback = status == News2Applicability.Status.NEWBORN
            ? "NEWS2 not validated for a newborn: see the newborn alerts (vitals recorded {0})"
            : "NEWS2 not validated in pregnancy / postpartum: see the maternity alerts (vitals recorded {0})";
        return new CdsCard(
            BpaCardText.name(text, protocol),
            text.get(key, fallback, readAt),
            raised ? CdsCard.Indicator.WARNING : CdsCard.Indicator.INFO,
            new Source(text.get("cds.bpa.source", "HMS Best-Practice Advisory"), null, null),
            null,
            null,
            null,
            null
        );
    }

    /** Most recent non-home bundle inside the lookback (vitals arrive DESC). */
    private static Optional<PatientVitalSign> latestClinicalVitals(List<PatientVitalSign> vitals) {
        LocalDateTime cutoff = LocalDateTime.now().minus(LOOKBACK);
        return vitals.stream()
            .filter(v -> v.getRecordedAt() != null && !v.getRecordedAt().isBefore(cutoff))
            .filter(v -> !PATIENT_REPORTED_SOURCE.equalsIgnoreCase(v.getSource()))
            .findFirst();
    }

    private CdsCard buildCard(BpaProtocol protocol, NewsScoreResult score,
                                     PatientVitalSign vitals, java.time.ZoneId zone) {
        StringBuilder detail = new StringBuilder(BpaCardText.summary(text, protocol))
            .append("\n\n").append(text.get("cds.bpa.news2.score", "NEWS2: {0} ({1} risk) — vitals recorded {2}",
                String.valueOf(score.total()),
                text.get("cds.bpa.news2.band." + score.riskBand().name(), score.riskBand().name().replace('_', '-')),
                BpaCardText.when(text, vitals.getRecordedAt(), zone)));
        if (!score.complete()) {
            detail.append("\n").append(text.get("cds.bpa.news2.incomplete",
                "⚠ Score is INCOMPLETE — not recorded: {0}. The true score can only be equal or higher.",
                String.join(", ", score.missingParameters().stream()
                    .map(m -> text.get("cds.bpa.news2.param." + m, m)).toList())));
        }
        if (score.anyParameterThree()) {
            detail.append("\n").append(text.get("cds.bpa.news2.parameterThree",
                "At least one parameter scores 3 — urgent review per policy."));
        }
        List<CdsLink> links = protocol.getProtocolUrl() == null
            ? null
            : List.of(new CdsLink(
                text.get("cds.bpa.news2.link", "RCP — NEWS2 and escalation matrix"),
                protocol.getProtocolUrl(),
                "absolute",
                null));
        return new CdsCard(
            BpaCardText.name(text, protocol),
            detail.toString(),
            CdsCard.Indicator.WARNING,
            new Source(text.get("cds.bpa.source", "HMS Best-Practice Advisory"), null, null),
            links,
            null,
            null,
            null // QA D1194: BpaRuleEngine gives the card its stable id (rule + level)
        );
    }
}
