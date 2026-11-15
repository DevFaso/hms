package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.service.vitals.SevereHypertension;
import com.example.hms.service.vitals.SevereHypertension.Reading;
import com.example.hms.service.vitals.SevereHypertension.Scope;

import java.util.ArrayList;
import java.util.List;

/**
 * QA D1890 — Best-Practice Advisory: severe hypertension after birth. The latest blood pressure in
 * the engine's window, from a vital set or a postpartum observation, is judged by
 * {@link SevereHypertension}, the rule the postpartum observation and every vitals write use; this
 * card speaks for its postpartum scope (systolic &ge; 160 OR diastolic &ge; 110 mmHg, taken up to 42
 * days after a recorded birth; r54: was 365). A match is a CRITICAL card asking for an urgent
 * obstetric review; the card never blocks anything. After 42 days the adult advisory
 * ({@link AdultSevereHypertensionRule}) judges the reading.
 *
 * <p>The pregnancy advisory ({@link PregnancyHypertensionProtocolRule}) stops at a recorded birth
 * (the engine drops the due date), so the two never speak about the same reading.
 *
 * <p>PROVISIONAL (user decision of 2026-11-14, pending the obstetric lead's approval).
 */
@org.springframework.stereotype.Service
public class PostpartumSevereHypertensionRule implements BpaRule {

    public static final String PROTOCOL_CODE = "POSTPARTUM_SEVERE_HYPERTENSION";

    private final SevereHypertension evaluator;
    private final CdsCardText text;

    public PostpartumSevereHypertensionRule(SevereHypertension evaluator, CdsCardText text) {
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
        if (latest == null) return List.of();
        SevereHypertension.Verdict verdict = evaluator.evaluate(context.patient(), latest);
        if (verdict == null || !verdict.raised() || verdict.scope() != Scope.POSTPARTUM) {
            return List.of();
        }
        return List.of(buildCard(latest, text.zoneOf(context.hospitalId())));
    }

    /** The most recent reading that carries a pressure, vital set or postpartum observation alike. */
    static Reading latest(BpaRuleContext context) {
        List<Reading> readings = new ArrayList<>(context.postpartumBloodPressures());
        for (PatientVitalSign v : context.recentVitals()) {
            readings.add(new Reading(v.getSystolicBpMmHg(), v.getDiastolicBpMmHg(), v.getRecordedAt(), v.getCreatedAt(),
                !com.example.hms.enums.OrganDamageSymptom.parse(v.getOrganDamageSymptoms()).isEmpty()));
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
