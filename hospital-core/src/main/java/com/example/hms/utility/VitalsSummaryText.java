package com.example.hms.utility;

import com.example.hms.model.PatientVitalSign;

import java.util.ArrayList;
import java.util.List;

/**
 * QA D628: the one-line vitals summary shown on the doctor worklist and on
 * the patient snapshot. It used English abbreviations ("HR", "BP") under a
 * French screen, and the worklist left the temperature out, so a febrile
 * patient (38.9 °C) read as normal. Every part is rendered from the message
 * bundle in the request locale, and the temperature always comes first.
 */
public final class VitalsSummaryText {

    private static final String SEPARATOR = " · ";

    private VitalsSummaryText() {
    }

    /** The summary of one bundle, or null when it carries none of these values. */
    public static String of(PatientVitalSign v) {
        if (v == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (v.getTemperatureCelsius() != null) {
            // D1002 class: "38,9" in French and Spanish, one decimal at most, no grouping.
            parts.add(MessageUtil.resolve("vitals.summary.temperature",
                DisplayNumbers.measure(v.getTemperatureCelsius(), 1, DisplayNumbers.requestLocale())));
        }
        if (v.getHeartRateBpm() != null) {
            parts.add(MessageUtil.resolve("vitals.summary.heartRate", v.getHeartRateBpm()));
        }
        if (v.getSystolicBpMmHg() != null && v.getDiastolicBpMmHg() != null) {
            parts.add(MessageUtil.resolve("vitals.summary.bloodPressure",
                v.getSystolicBpMmHg(), v.getDiastolicBpMmHg()));
        }
        if (v.getSpo2Percent() != null) {
            parts.add(MessageUtil.resolve("vitals.summary.spo2", v.getSpo2Percent()));
        }
        return parts.isEmpty() ? null : String.join(SEPARATOR, parts);
    }
}
