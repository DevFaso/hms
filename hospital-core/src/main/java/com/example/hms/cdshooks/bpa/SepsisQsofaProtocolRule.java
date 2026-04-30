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
 * Best-Practice Advisory: qSOFA-based sepsis screening. Fires when at
 * least two of the three qSOFA criteria are met within the last 6 hours:
 * <ul>
 *   <li>Respiratory rate ≥ 22 / min</li>
 *   <li>Systolic blood pressure ≤ 100 mmHg</li>
 *   <li>Altered mental status (encoded in the active problem list)</li>
 * </ul>
 *
 * <p>Why qSOFA over SIRS for v0: qSOFA needs only three bedside fields
 * (RR, sBP, mentation), all of which are routinely captured in HMS
 * vitals + the problem list, and it correlates better with in-hospital
 * mortality. SIRS additionally needs WBC and core temperature timing
 * tighter than HMS currently records.
 *
 * <p>Advisory only — never blocks the chart load.
 */
@Service
public class SepsisQsofaProtocolRule implements BpaRule {

    public static final String PROTOCOL_CODE = "SEPSIS_QSOFA";
    private static final Duration LOOKBACK = Duration.ofHours(6);

    private static final int RR_THRESHOLD = 22;
    private static final int SBP_THRESHOLD = 100;
    private static final int QSOFA_TRIGGER = 2;

    /**
     * Heuristic substrings (case-insensitive) used to detect "altered
     * mental status" on the active problem list. Kept here rather than
     * in V64 because they are rule logic, not catalog data.
     */
    private static final List<String> AMS_HINTS = List.of(
        "altered mental status", "ams", "delirium", "encephalopathy",
        "confusion", "decreased loc", "gcs"
    );

    private final BpaProtocolRepository protocolRepository;

    public SepsisQsofaProtocolRule(BpaProtocolRepository protocolRepository) {
        this.protocolRepository = protocolRepository;
    }

    @Override
    public String id() {
        return PROTOCOL_CODE;
    }

    @Override
    public List<CdsCard> evaluate(BpaRuleContext context) {
        if (context == null || !context.hasPatient()) return List.of();

        QsofaScore score = scoreQsofa(context.recentVitals(), context.activeProblems());
        if (score.total() < QSOFA_TRIGGER) return List.of();

        Optional<BpaProtocol> protocol = protocolRepository.findByProtocolCodeAndActiveTrue(PROTOCOL_CODE);
        if (protocol.isEmpty()) return List.of();

        return List.of(buildCard(protocol.get(), score));
    }

    QsofaScore scoreQsofa(List<PatientVitalSign> vitals, List<PatientProblem> problems) {
        LocalDateTime cutoff = LocalDateTime.now().minus(LOOKBACK);
        boolean rrHigh = false;
        boolean sbpLow = false;
        for (PatientVitalSign v : vitals) {
            if (v.getRecordedAt() == null || v.getRecordedAt().isBefore(cutoff)) continue;
            if (!rrHigh && v.getRespiratoryRateBpm() != null
                && v.getRespiratoryRateBpm() >= RR_THRESHOLD) {
                rrHigh = true;
            }
            if (!sbpLow && v.getSystolicBpMmHg() != null
                && v.getSystolicBpMmHg() <= SBP_THRESHOLD) {
                sbpLow = true;
            }
            if (rrHigh && sbpLow) break;
        }
        boolean ams = problems.stream().anyMatch(p ->
            matchesAny(p.getProblemDisplay(), AMS_HINTS)
                || matchesAny(p.getProblemCode(), AMS_HINTS));
        return new QsofaScore(rrHigh, sbpLow, ams);
    }

    private static boolean matchesAny(String value, List<String> hints) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return hints.stream().anyMatch(lower::contains);
    }

    private static CdsCard buildCard(BpaProtocol protocol, QsofaScore score) {
        String detail = protocol.getSummary()
            + "\n\nqSOFA criteria currently met: " + score.total() + "/3"
            + "\n  • RR ≥ 22: " + (score.rrHigh() ? "yes" : "no")
            + "\n  • sBP ≤ 100: " + (score.sbpLow() ? "yes" : "no")
            + "\n  • Altered mental status: " + (score.ams() ? "yes" : "no");
        List<CdsLink> links = protocol.getProtocolUrl() == null
            ? null
            : List.of(new CdsLink(
                "Surviving Sepsis Campaign — Hour-1 bundle",
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

    /** Internal qSOFA score breakdown — package-private for tests. */
    record QsofaScore(boolean rrHigh, boolean sbpLow, boolean ams) {
        int total() {
            return (rrHigh ? 1 : 0) + (sbpLow ? 1 : 0) + (ams ? 1 : 0);
        }
    }
}
