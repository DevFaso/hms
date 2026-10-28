package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
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
 *   <li>Altered mental status: a consciousness level other than Alert
 *       (ACVPU, recorded with the vitals) in the window, or an altered
 *       mentation entry on the active problem list</li>
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
    private static final String BULLET = "\n  • ";
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
        "confusion", "decreased loc", "gcs",
        // QA D953: the problem list is written in French or Spanish here.
        "altération de la conscience", "trouble de la conscience", "troubles de la conscience",
        "conscience altérée", "délire", "encéphalopathie", "obnubilation",
        "confusional", "alteración de la conciencia", "conciencia alterada", "encefalopatía", "delirio"
    );

    private final BpaProtocolRepository protocolRepository;
    private final CdsCardText text;

    /** English-only, for rules built outside Spring. */
    public SepsisQsofaProtocolRule(BpaProtocolRepository protocolRepository) {
        this(protocolRepository, CdsCardText.ENGLISH);
    }

    /** QA D217: the card is written in the caller's language, like the D61 rules. */
    @org.springframework.beans.factory.annotation.Autowired
    public SepsisQsofaProtocolRule(BpaProtocolRepository protocolRepository, CdsCardText text) {
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
        // QA D606: qSOFA is an adult score (Sepsis-3). A child's normal
        // respiratory rate and blood pressure meet two adult criteria, so a
        // healthy 6-year-old got a "start antibiotics" card. No card under 18.
        if (!appliesTo(context.patient())) return List.of();

        QsofaScore score = scoreQsofa(context.recentVitals(), context.activeProblems());
        if (score.total() < QSOFA_TRIGGER) return List.of();

        Optional<BpaProtocol> protocol = protocolRepository.findByProtocolCodeAndActiveTrue(PROTOCOL_CODE);
        if (protocol.isEmpty()) return List.of();

        return List.of(buildCard(protocol.get(), score));
    }

    /** qSOFA is validated for adults only. */
    public static final int MIN_AGE_YEARS = 18;

    /**
     * QA D606: whether qSOFA may be applied to this patient. A patient with
     * no recorded birth date keeps the adult rule, as NEWS2 does (QA D518).
     */
    static boolean appliesTo(com.example.hms.model.Patient patient) {
        if (patient == null || patient.getDateOfBirth() == null) {
            return true;
        }
        return java.time.Period.between(patient.getDateOfBirth(),
            java.time.LocalDate.now(java.time.ZoneId.systemDefault())).getYears() >= MIN_AGE_YEARS;
    }

    QsofaScore scoreQsofa(List<PatientVitalSign> vitals, List<PatientProblem> problems) {
        LocalDateTime cutoff = LocalDateTime.now().minus(LOOKBACK);
        boolean rrHigh = false;
        boolean sbpLow = false;
        boolean alteredConsciousness = false;
        for (PatientVitalSign v : vitals) {
            if (v.getRecordedAt() == null || v.getRecordedAt().isBefore(cutoff)) continue;
            // QA D953: the triage nurse records mentation as ACVPU with the
            // vitals. Anything but Alert (new confusion, responds to voice or
            // pain, unresponsive) is altered mentation for qSOFA (GCS < 15).
            if (v.getConsciousnessLevel() != null
                && v.getConsciousnessLevel() != com.example.hms.enums.ConsciousnessLevel.ALERT) {
                alteredConsciousness = true;
            }
            if (!rrHigh && v.getRespiratoryRateBpm() != null
                && v.getRespiratoryRateBpm() >= RR_THRESHOLD) {
                rrHigh = true;
            }
            if (!sbpLow && v.getSystolicBpMmHg() != null
                && v.getSystolicBpMmHg() <= SBP_THRESHOLD) {
                sbpLow = true;
            }
        }
        boolean ams = alteredConsciousness || problems.stream().anyMatch(p ->
            matchesAny(p.getProblemDisplay(), AMS_HINTS)
                || matchesAny(p.getProblemCode(), AMS_HINTS));
        return new QsofaScore(rrHigh, sbpLow, ams);
    }

    private static boolean matchesAny(String value, List<String> hints) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return hints.stream().anyMatch(lower::contains);
    }

    private CdsCard buildCard(BpaProtocol protocol, QsofaScore score) {
        String detail = BpaCardText.summary(text, protocol)
            + "\n\n" + text.get("cds.bpa.qsofa.met", "qSOFA criteria currently met: {0}/3", String.valueOf(score.total()))
            + BULLET + text.get("cds.bpa.qsofa.rr", "RR ≥ 22: {0}", BpaCardText.yesNo(text, score.rrHigh()))
            + BULLET + text.get("cds.bpa.qsofa.sbp", "sBP ≤ 100: {0}", BpaCardText.yesNo(text, score.sbpLow()))
            + BULLET + text.get("cds.bpa.qsofa.ams", "Altered mental status: {0}", BpaCardText.yesNo(text, score.ams()));
        List<CdsLink> links = protocol.getProtocolUrl() == null
            ? null
            : List.of(new CdsLink(
                text.get("cds.bpa.qsofa.link", "Surviving Sepsis Campaign — Hour-1 bundle"),
                protocol.getProtocolUrl(),
                "absolute",
                null));
        return new CdsCard(
            BpaCardText.name(text, protocol),
            detail,
            CdsCard.Indicator.WARNING,
            new Source(text.get("cds.bpa.source", "HMS Best-Practice Advisory"), null, null),
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
