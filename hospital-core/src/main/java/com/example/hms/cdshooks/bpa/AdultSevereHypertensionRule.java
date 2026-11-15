package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.service.vitals.SevereHypertension;
import com.example.hms.service.vitals.SevereHypertension.Reading;

import java.util.List;

/**
 * r54 — Best-Practice Advisory: severe hypertension in an adult. The latest blood pressure in the
 * engine's window (a vital set or a postpartum observation, the same reading the postpartum advisory
 * reads) is judged by {@link SevereHypertension}, the rule every vitals write uses. For an adult (18
 * or more, or an unknown birth date) who is neither pregnant nor within 42 days of a birth, systolic
 * &ge; 180 OR diastolic &ge; 120 mmHg is a WARNING card asking to recheck at rest and look for organ
 * damage; a CRITICAL card when the reading carries a ticked sign of organ damage. A later reading
 * below the thresholds clears it, since only the latest reading is judged. The card never blocks.
 *
 * <p>The obstetric scopes speak through {@link PregnancyHypertensionProtocolRule} and
 * {@link PostpartumSevereHypertensionRule}, so no reading gets two blood-pressure cards.
 *
 * <p>PROVISIONAL (user decision of 2026-11-15, pending the clinical lead's approval).
 */
@org.springframework.stereotype.Service
public class AdultSevereHypertensionRule implements BpaRule {

    public static final String PROTOCOL_CODE = "ADULT_SEVERE_HYPERTENSION";

    private final SevereHypertension evaluator;
    private final CdsCardText text;

    public AdultSevereHypertensionRule(SevereHypertension evaluator, CdsCardText text) {
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
        Reading latest = PostpartumSevereHypertensionRule.latest(context);
        if (latest == null) return List.of();
        SevereHypertension.Verdict verdict = evaluator.evaluate(context.patient(), context.hospitalId(), latest);
        if (verdict == null || !verdict.adult()) {
            return List.of();
        }
        return List.of(buildCard(latest, verdict.level() == SevereHypertension.Level.URGENT,
            text.zoneOf(context.hospitalId())));
    }

    private CdsCard buildCard(Reading r, boolean urgent, java.time.ZoneId zone) {
        String reading = text.get("cds.bpa.adultHtn.reading",
            "Blood pressure {0}/{1} mmHg ({2}).",
            dash(r.systolic()), dash(r.diastolic()), BpaCardText.when(text, r.takenAt(), zone));
        String summary = urgent
            ? text.get("cds.bpa.ADULT_SEVERE_HYPERTENSION.summaryUrgent",
                "Severe hypertension with a sign of organ damage (systolic >= 180 mmHg or diastolic >= 120 mmHg): "
                    + "urgent medical review required.")
            : text.get("cds.bpa.ADULT_SEVERE_HYPERTENSION.summary",
                "Severe hypertension (systolic >= 180 mmHg or diastolic >= 120 mmHg): recheck the blood pressure "
                    + "at rest and look for signs of organ damage.");
        return new CdsCard(
            text.get("cds.bpa.ADULT_SEVERE_HYPERTENSION.name", "Severe hypertension"),
            summary + "\n\n" + reading,
            urgent ? CdsCard.Indicator.CRITICAL : CdsCard.Indicator.WARNING,
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
