package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.service.maternity.PostpartumSevereHypertension;
import com.example.hms.service.maternity.PostpartumSevereHypertension.Reading;

import java.util.ArrayList;
import java.util.List;

/**
 * QA D1890 — Best-Practice Advisory: severe hypertension after birth. The latest blood pressure in
 * the engine's window, from a vital set or a postpartum observation, is judged by
 * {@link PostpartumSevereHypertension} (systolic &ge; 160 OR diastolic &ge; 110 mmHg, taken after a
 * recorded birth): the same rule the postpartum observation and every vitals write use. A match is a
 * CRITICAL card asking for an urgent obstetric review; the card never blocks anything.
 *
 * <p>The pregnancy advisory ({@link PregnancyHypertensionProtocolRule}) stops at a recorded birth
 * (the engine drops the due date), so the two never speak about the same reading.
 *
 * <p>PROVISIONAL (user decision of 2026-11-14, pending the obstetric lead's approval).
 */
@org.springframework.stereotype.Service
public class PostpartumSevereHypertensionRule implements BpaRule {

    public static final String PROTOCOL_CODE = "POSTPARTUM_SEVERE_HYPERTENSION";

    private final PostpartumSevereHypertension evaluator;
    private final CdsCardText text;

    public PostpartumSevereHypertensionRule(PostpartumSevereHypertension evaluator, CdsCardText text) {
        this.evaluator = evaluator;
        this.text = text;
    }

    @Override
    public String id() {
        return PROTOCOL_CODE;
    }

    @Override
    public List<CdsCard> evaluate(BpaRuleContext context) {
        if (context == null || !context.hasPatient() || evaluator == null) return List.of();
        Reading latest = latest(context);
        if (latest == null || !evaluator.applies(context.patient().getId(), latest.systolic(), latest.diastolic(),
            latest.takenAt())) {
            return List.of();
        }
        return List.of(buildCard(latest, text.zoneOf(context.hospitalId())));
    }

    /** The most recent reading that carries a pressure, vital set or postpartum observation alike. */
    static Reading latest(BpaRuleContext context) {
        List<Reading> readings = new ArrayList<>(context.postpartumBloodPressures());
        for (PatientVitalSign v : context.recentVitals()) {
            readings.add(new Reading(v.getSystolicBpMmHg(), v.getDiastolicBpMmHg(), v.getRecordedAt(), v.getCreatedAt()));
        }
        Reading latest = null;
        for (Reading r : readings) {
            if (r.systolic() == null && r.diastolic() == null) continue;
            // As the pregnancy advisory: a reading with no time is older than any dated one.
            if (latest == null || (r.takenAt() != null && (latest.takenAt() == null
                || r.takenAt().isAfter(latest.takenAt())))) {
                latest = r;
            }
        }
        return latest;
    }

    private CdsCard buildCard(Reading r, java.time.ZoneId zone) {
        String reading = text.get("cds.bpa.postpartumHtn.reading",
            "Blood pressure {0}/{1} mmHg ({2}).",
            dash(r.systolic()), dash(r.diastolic()), BpaCardText.when(text, r.takenAt(), zone));
        String detail = text.get("cds.bpa." + PROTOCOL_CODE + ".summary",
            "Severe blood pressure after birth (systolic >= 160 mmHg or diastolic >= 110 mmHg): "
                + "urgent obstetric review required.")
            + "\n\n" + reading;
        return new CdsCard(
            text.get("cds.bpa." + PROTOCOL_CODE + ".name", "Severe hypertension after birth"),
            detail,
            CdsCard.Indicator.CRITICAL,
            new Source(text.get("cds.bpa.source", "HMS Best-Practice Advisory"), null, null),
            null,
            null,
            null,
            null // QA D1194: BpaRuleEngine gives the card its stable id (rule + level)
        );
    }

    private static String dash(Integer value) {
        return value == null ? "—" : String.valueOf(value);
    }
}
