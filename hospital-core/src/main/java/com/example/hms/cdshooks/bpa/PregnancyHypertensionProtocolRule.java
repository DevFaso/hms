package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.BpaProtocolRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * QA D971 — Best-Practice Advisory: hypertension in pregnancy. A pregnant
 * patient (a current maternal history with a due date that has not passed by
 * more than two weeks) whose latest blood pressure in the engine's window is
 * at or above the hypertension cutoff gets a card on the chart, outside a
 * labour episode too. Before this the only pregnancy blood-pressure alerts
 * lived inside the partograph, so 152/102 at 22 weeks raised nothing.
 *
 * <p>Thresholds are the ones the labour alerts already use
 * ({@code LaborServiceImpl.HTN_*} and {@code SEVERE_HTN_*}), which are the
 * widely used cutoffs: hypertension at systolic ≥ 140 or diastolic ≥ 90 mmHg,
 * severe at systolic ≥ 160 or diastolic ≥ 110 mmHg.
 * {@code PregnancyHypertensionThresholdsTest} keeps the two in step.
 *
 * <p>The protocol row is optional: with none seeded the card is written from
 * the bundle; a row an administrator switched off silences the rule.
 */
@Service
public class PregnancyHypertensionProtocolRule implements BpaRule {

    public static final String PROTOCOL_CODE = "PREGNANCY_HYPERTENSION";
    public static final int HTN_SYSTOLIC = 140;
    public static final int HTN_DIASTOLIC = 90;
    public static final int SEVERE_HTN_SYSTOLIC = 160;
    public static final int SEVERE_HTN_DIASTOLIC = 110;
    /** Naegele: a due date is 280 days after the last menstrual period. */
    static final int PREGNANCY_DAYS = 280;
    /** Post-term is 42 weeks, two weeks past the due date; beyond it the pregnancy is not assumed ongoing. */
    static final int POST_TERM_GRACE_DAYS = 14;

    private final BpaProtocolRepository protocolRepository;
    private final CdsCardText text;

    /** English-only, for rules built outside Spring. */
    public PregnancyHypertensionProtocolRule(BpaProtocolRepository protocolRepository) {
        this(protocolRepository, CdsCardText.ENGLISH);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PregnancyHypertensionProtocolRule(BpaProtocolRepository protocolRepository, CdsCardText text) {
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
        LocalDate today = LocalDate.now(java.time.ZoneId.systemDefault());
        if (!isPregnant(context.estimatedDueDate(), today)) return List.of();

        PatientVitalSign latest = latestBloodPressure(context.recentVitals());
        if (latest == null || !isHypertensive(latest)) return List.of();

        Optional<BpaProtocol> protocol = protocolRepository.findByProtocolCode(PROTOCOL_CODE);
        if (protocol.isPresent() && !protocol.get().isActive()) return List.of();

        return List.of(buildCard(latest, gestationalWeeks(context.estimatedDueDate(), today),
            text.zoneOf(context.hospitalId())));
    }

    static boolean isPregnant(LocalDate dueDate, LocalDate today) {
        if (dueDate == null) return false;
        return !today.isBefore(dueDate.minusDays(PREGNANCY_DAYS))
            && !today.isAfter(dueDate.plusDays(POST_TERM_GRACE_DAYS));
    }

    static long gestationalWeeks(LocalDate dueDate, LocalDate today) {
        return ChronoUnit.DAYS.between(dueDate.minusDays(PREGNANCY_DAYS), today) / 7;
    }

    static boolean isHypertensive(PatientVitalSign v) {
        return atOrAbove(v.getSystolicBpMmHg(), HTN_SYSTOLIC) || atOrAbove(v.getDiastolicBpMmHg(), HTN_DIASTOLIC);
    }

    static boolean isSevere(PatientVitalSign v) {
        return atOrAbove(v.getSystolicBpMmHg(), SEVERE_HTN_SYSTOLIC)
            || atOrAbove(v.getDiastolicBpMmHg(), SEVERE_HTN_DIASTOLIC);
    }

    private static boolean atOrAbove(Integer value, int threshold) {
        return value != null && value >= threshold;
    }

    /** The most recent reading that carries a blood pressure (vitals arrive most recent first). */
    private static PatientVitalSign latestBloodPressure(List<PatientVitalSign> vitals) {
        PatientVitalSign latest = null;
        for (PatientVitalSign v : vitals) {
            if (v.getSystolicBpMmHg() == null && v.getDiastolicBpMmHg() == null) continue;
            // Review of #971: a reading with no time is older than any dated one.
            if (latest == null || (v.getRecordedAt() != null && (latest.getRecordedAt() == null
                || v.getRecordedAt().isAfter(latest.getRecordedAt())))) {
                latest = v;
            }
        }
        return latest;
    }

    private CdsCard buildCard(PatientVitalSign v, long weeks, java.time.ZoneId zone) {
        boolean severe = isSevere(v);
        String reading = text.get("cds.bpa.pregnancyHtn.reading",
            "Blood pressure {0}/{1} mmHg at {2} weeks of pregnancy ({3}).",
            dash(v.getSystolicBpMmHg()), dash(v.getDiastolicBpMmHg()), String.valueOf(weeks),
            BpaCardText.when(text, v.getRecordedAt(), zone));
        String detail = text.get("cds.bpa." + PROTOCOL_CODE + ".summary",
            "Hypertension in pregnancy (systolic >= 140 or diastolic >= 90 mmHg). Recheck the blood pressure, "
                + "test for proteinuria and look for warning signs (headache, visual disturbance, epigastric "
                + "pain); manage per the local pre-eclampsia protocol.")
            + "\n\n" + reading
            + (severe ? "\n" + text.get("cds.bpa.pregnancyHtn.severe",
                "Severe range (systolic >= 160 or diastolic >= 110 mmHg): urgent obstetric review.") : "");
        return new CdsCard(
            text.get("cds.bpa." + PROTOCOL_CODE + ".name", "Hypertension in pregnancy"),
            detail,
            severe ? CdsCard.Indicator.CRITICAL : CdsCard.Indicator.WARNING,
            new Source(text.get("cds.bpa.source", "HMS Best-Practice Advisory"), null, null),
            null,
            null,
            null,
            java.util.UUID.randomUUID().toString()
        );
    }

    private static String dash(Integer value) {
        return value == null ? "—" : String.valueOf(value);
    }
}
