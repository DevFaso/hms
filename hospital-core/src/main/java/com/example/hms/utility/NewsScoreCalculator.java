package com.example.hms.utility;

import com.example.hms.enums.ConsciousnessLevel;
import com.example.hms.model.PatientVitalSign;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWS2 (National Early Warning Score 2, RCP 2017) over a single vitals
 * bundle (P3 #25b). Seven parameters: respiratory rate, SpO₂ (scale 1),
 * supplemental oxygen, temperature, systolic BP, heart rate, ACVPU
 * consciousness.
 *
 * <p><strong>Partial scoring is explicit, never silent.</strong> A
 * missing parameter contributes 0 — the only computable choice — but the
 * result names every missing parameter and flags itself incomplete, so
 * every consumer (BPA card, portal chip) can show "score 4 of a possible
 * incomplete set" rather than a confident-looking under-score. Silently
 * partial scores under-score exactly the deteriorating patients the
 * score exists to catch; refusing to score at all has no precedent in
 * this codebase and would hide what IS known.
 *
 * <p>SpO₂ is scored on scale 1 only — scale 2 (target 88–92%, for
 * hypercapnic respiratory failure) needs a per-patient care-plan flag
 * that does not exist; deferred with the feature notes.
 */
public final class NewsScoreCalculator {

    /** NEWS2 aggregate risk bands per the RCP escalation matrix. */
    public enum RiskBand {
        LOW,
        /** Any single parameter scoring 3 — urgent review even at a low total. */
        LOW_MEDIUM,
        MEDIUM,
        HIGH
    }

    public record NewsScoreResult(
        int total,
        boolean anyParameterThree,
        RiskBand riskBand,
        List<String> missingParameters
    ) {
        public boolean complete() {
            return missingParameters.isEmpty();
        }
    }

    private NewsScoreCalculator() {
    }

    public static NewsScoreResult score(PatientVitalSign vital) {
        List<String> missing = new ArrayList<>();
        int total = 0;
        boolean anyThree = false;

        Integer rr = vital.getRespiratoryRateBpm();
        if (rr == null) {
            missing.add("respiratoryRate");
        } else {
            int s = scoreRespiratoryRate(rr);
            total += s;
            anyThree |= s == 3;
        }

        Integer spo2 = vital.getSpo2Percent();
        if (spo2 == null) {
            missing.add("spo2");
        } else {
            int s = scoreSpo2(spo2);
            total += s;
            anyThree |= s == 3;
        }

        Boolean onOxygen = vital.getOnOxygen();
        if (onOxygen == null) {
            missing.add("onOxygen");
        } else if (Boolean.TRUE.equals(onOxygen)) {
            total += 2;
        }

        Double temperature = vital.getTemperatureCelsius();
        if (temperature == null) {
            missing.add("temperature");
        } else {
            int s = scoreTemperature(temperature);
            total += s;
            anyThree |= s == 3;
        }

        Integer sbp = vital.getSystolicBpMmHg();
        if (sbp == null) {
            missing.add("systolicBp");
        } else {
            int s = scoreSystolicBp(sbp);
            total += s;
            anyThree |= s == 3;
        }

        Integer hr = vital.getHeartRateBpm();
        if (hr == null) {
            missing.add("heartRate");
        } else {
            int s = scoreHeartRate(hr);
            total += s;
            anyThree |= s == 3;
        }

        ConsciousnessLevel consciousness = vital.getConsciousnessLevel();
        if (consciousness == null) {
            missing.add("consciousness");
        } else if (consciousness != ConsciousnessLevel.ALERT) {
            total += 3;
            anyThree = true;
        }

        return new NewsScoreResult(total, anyThree, band(total, anyThree), List.copyOf(missing));
    }

    private static RiskBand band(int total, boolean anyThree) {
        if (total >= 7) return RiskBand.HIGH;
        if (total >= 5) return RiskBand.MEDIUM;
        if (anyThree) return RiskBand.LOW_MEDIUM;
        return RiskBand.LOW;
    }

    static int scoreRespiratoryRate(int rr) {
        if (rr <= 8) return 3;
        if (rr <= 11) return 1;
        if (rr <= 20) return 0;
        if (rr <= 24) return 2;
        return 3;
    }

    static int scoreSpo2(int spo2) {
        if (spo2 <= 91) return 3;
        if (spo2 <= 93) return 2;
        if (spo2 <= 95) return 1;
        return 0;
    }

    static int scoreTemperature(double celsius) {
        if (celsius <= 35.0) return 3;
        if (celsius <= 36.0) return 1;
        if (celsius <= 38.0) return 0;
        if (celsius <= 39.0) return 1;
        return 2;
    }

    static int scoreSystolicBp(int sbp) {
        if (sbp <= 90) return 3;
        if (sbp <= 100) return 2;
        if (sbp <= 110) return 1;
        if (sbp <= 219) return 0;
        return 3;
    }

    static int scoreHeartRate(int hr) {
        if (hr <= 40) return 3;
        if (hr <= 50) return 1;
        if (hr <= 90) return 0;
        if (hr <= 110) return 1;
        if (hr <= 130) return 2;
        return 3;
    }
}
