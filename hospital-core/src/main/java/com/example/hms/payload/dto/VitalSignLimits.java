package com.example.hms.payload.dto;

/**
 * Physiological plausibility bounds shared by every request that records a
 * vital sign (nurse station, triage, the vitals API, patient home readings).
 *
 * <p>These are not alarm thresholds — those live in the clinical-significance
 * and NEWS2 scoring. They reject values no living patient can present with and
 * that only a typing slip produces (45 °C, a heart rate of 400, SpO2 150 %), so
 * a typo can never become part of the chart. Each bound leaves room for the
 * real extremes the wards see:
 * <ul>
 *   <li>Temperature 25.0–44.0 °C: low-reading clinical thermometers stop at
 *       25 °C (severe accidental hypothermia is still charted there), and core
 *       temperatures above 44 °C are not survivable long enough to be measured
 *       at a bedside.</li>
 *   <li>Heart rate 20–300 bpm: profound bradycardia to neonatal SVT.</li>
 *   <li>Respiratory rate 4–80 /min: agonal breathing to neonatal tachypnoea.</li>
 *   <li>Systolic 40–300, diastolic 20–200 mmHg: shock to hypertensive crisis.</li>
 *   <li>SpO2 0–100 %: a saturation is a percentage.</li>
 *   <li>Glucose 20–800 mg/dL, weight 0.2–400 kg (sub-1 kg NICU neonates),
 *       height 20–250 cm, head circumference 15–70 cm.</li>
 * </ul>
 * The portal's vital forms mirror these numbers.
 */
public final class VitalSignLimits {

    public static final String TEMPERATURE_MIN_C = "25.0";
    public static final String TEMPERATURE_MAX_C = "44.0";
    public static final int HEART_RATE_MIN = 20;
    public static final int HEART_RATE_MAX = 300;
    public static final int RESPIRATORY_RATE_MIN = 4;
    public static final int RESPIRATORY_RATE_MAX = 80;
    public static final int SYSTOLIC_MIN = 40;
    public static final int SYSTOLIC_MAX = 300;
    public static final int DIASTOLIC_MIN = 20;
    public static final int DIASTOLIC_MAX = 200;
    public static final int SPO2_MIN = 0;
    public static final int SPO2_MAX = 100;
    public static final int GLUCOSE_MIN = 20;
    public static final int GLUCOSE_MAX = 800;
    public static final String WEIGHT_MIN_KG = "0.2";
    public static final String WEIGHT_MAX_KG = "400.0";
    public static final String HEIGHT_MIN_CM = "20.0";
    public static final String HEIGHT_MAX_CM = "250.0";
    public static final String HEAD_CIRCUMFERENCE_MIN_CM = "15.0";
    public static final String HEAD_CIRCUMFERENCE_MAX_CM = "70.0";

    private VitalSignLimits() {
    }

    /**
     * D111: a diastolic pressure at or above the systolic one (60/150) is a
     * swapped entry, not a reading. A missing value is not judged here
     * (a palpated systolic alone is a real reading).
     */
    public static boolean bloodPressureOrdered(Integer systolic, Integer diastolic) {
        return systolic == null || diastolic == null || diastolic < systolic;
    }

}
