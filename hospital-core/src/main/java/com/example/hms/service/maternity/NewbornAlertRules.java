package com.example.hms.service.maternity;

import com.example.hms.enums.GlucoseUnit;
import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.NewbornAlertType;
import com.example.hms.enums.NewbornDangerSignObservation;
import com.example.hms.enums.NewbornFollowUpAction;
import com.example.hms.payload.dto.VitalSignLimits;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The newborn assessment's warnings. The principle is warnings and escalation, never a
 * limit that prevents saving an observation: a newborn's real extremes are the readings
 * that most need the alert, and there is no sourced universal neonatal ceiling.
 *
 * <p>Sources, supplied by the user for QA D1424 (round 34):
 * <ul>
 *   <li>Royal Children's Hospital, "Acceptable ranges for physiological variables", Term row
 *       (last updated July 2023): heart rate 120-170 bpm, respiratory rate 25-60 breaths/min.
 *       It describes acceptable ranges for unwell children, not data-entry limits, so a value
 *       outside reads "outside the acceptable range" and is never refused for it.</li>
 *   <li>NICE NG195, table 2 (only partly verified, pending a clinician's check against the
 *       original): concern at a respiratory rate of 60 or more, a persistent heart
 *       rate of 160 or more or below 100, SpO2 below 90 % in air, a temperature below 36 °C
 *       or of 38 °C or more unexplained by the environment. These are possible-infection
 *       indicators, not a complete neonatal protocol, and a different context from the WHO
 *       pathway below: where both speak, the stricter stays.</li>
 *   <li>{@value #SELECTED_PATHWAY}: WHO recommendations on maternal and newborn care for a
 *       positive postnatal experience (2022), the pathway selected for the newborn's danger signs.
 *       PROVISIONAL (CR-04): not clinically approved, awaiting the named approver. The method of
 *       measurement is not recorded on the assessment.</li>
 *   <li>Canadian Paediatric Society statement (posted Nov 20, 2019, updated Mar 28, 2025): glucose below 2.6 mmol/L (about
 *       47 mg/dL) in the first 72 h of life, framed for at-risk newborns under its pathway,
 *       and below 3.3 mmol/L (about 59 mg/dL) after 72 h.</li>
 * </ul>
 *
 * <p>Bands older than these sources and stricter than them stay and are marked below: a
 * respiratory rate below 30 and a glucose below 45 mg/dL are urgent. Nothing is sourced for a
 * preterm baby, or for SpO2 in the first minutes after birth: no other number is applied, and
 * the wording says so.
 */
public final class NewbornAlertRules {

    public static final int REFERENCE_HEART_RATE_LOW = 120;
    public static final int REFERENCE_HEART_RATE_HIGH = 170;
    public static final int REFERENCE_RESPIRATORY_LOW = 25;
    public static final int REFERENCE_RESPIRATORY_HIGH = 60;

    public static final int CONCERN_HEART_RATE_LOW = 100;
    public static final int CONCERN_HEART_RATE_HIGH = 160;
    public static final int CONCERN_RESPIRATORY_HIGH = 60;
    public static final int CONCERN_SPO2 = 90;
    /** NICE's infection indicators: a different context from {@link #SELECTED_PATHWAY}. Below 36.0 stays urgent. */
    public static final double NICE_INFECTION_TEMPERATURE_LOW = 36.0;
    public static final double NICE_INFECTION_TEMPERATURE_HIGH = 38.0;

    /** The selected danger-sign pathway. PROVISIONAL: awaiting the named clinical approver. */
    public static final String SELECTED_PATHWAY = "WHO_POSTNATAL_2022_PROVISIONAL";
    /** CR-04, {@link #SELECTED_PATHWAY}: above this is a danger sign. 37.5 itself is not. PROVISIONAL. */
    public static final double WHO_DANGER_TEMPERATURE_ABOVE = 37.5;

    public static final int GLUCOSE_HOURS_FIRST_PERIOD = 72;
    public static final double GLUCOSE_FIRST_PERIOD_MMOL = 2.6;
    public static final int GLUCOSE_FIRST_PERIOD_MG_DL = 47;
    public static final double GLUCOSE_AFTER_MMOL = 3.3;
    public static final int GLUCOSE_AFTER_MG_DL = 59;

    /** Older than the sources and stricter: stays urgent at every age. */
    public static final int GLUCOSE_CRITICAL_MG_DL = 45;
    /** Older than the sources and stricter: stays urgent. */
    public static final int RESPIRATORY_LOW = 30;
    private static final double CAUTION_TEMPERATURE_LOW = 36.5;
    private static final double CAUTION_TEMPERATURE_HIGH = 37.5;

    /** The gestational age below which a birth is preterm (the labour module's own). */
    public static final int PRETERM_BELOW_WEEKS = 37;

    /**
     * What the rules read. {@code enteredMmol} and {@code enteredMgDl} hold a glucose as it was
     * entered, before it was rounded to the column's whole mg/dL: it is judged as entered.
     */
    public record Reading(Double temperatureCelsius, Integer heartRateBpm, Integer respirationsPerMin,
                          Integer spo2Percent, Integer glucoseMgDl, Double enteredMmol,
                          Integer systolicBpMmHg, Integer diastolicBpMmHg,
                          Long hoursSinceBirth, boolean preterm, boolean previousHeartRateOfConcern,
                          Double enteredMgDl) {
        public Reading(Double temperatureCelsius, Integer heartRateBpm, Integer respirationsPerMin,
                       Integer spo2Percent, Integer glucoseMgDl, Double enteredMmol,
                       Integer systolicBpMmHg, Integer diastolicBpMmHg,
                       Long hoursSinceBirth, boolean preterm, boolean previousHeartRateOfConcern) {
            this(temperatureCelsius, heartRateBpm, respirationsPerMin, spo2Percent, glucoseMgDl, enteredMmol,
                systolicBpMmHg, diastolicBpMmHg, hoursSinceBirth, preterm, previousHeartRateOfConcern, null);
        }
    }

    /**
     * CR-05: what was observed about the non-vital danger signs of {@link #SELECTED_PATHWAY}. These
     * are not all of its signs: early jaundice is not recorded, so nothing here may claim full
     * coverage. Read only from these structured values, never from free text. PROVISIONAL.
     */
    public record DangerSigns(NewbornDangerSignObservation poorFeeding, NewbornDangerSignObservation convulsions,
                              NewbornDangerSignObservation severeChestIndrawing,
                              NewbornDangerSignObservation absentSpontaneousMovement) {
    }

    /**
     * One warning, and the follow-up it requests. CR-06: a finding recommends; it never records
     * that treatment was given. Whether support was started is only what the clinician submitted
     * (NewbornSupportFlagGuardTest).
     */
    public record Finding(NewbornAlertType type, NewbornAlertSeverity severity, String code,
                          NewbornFollowUpAction followUp) {
        private static Finding of(NewbornAlertType type, NewbornAlertSeverity severity, String code) {
            return new Finding(type, severity, code, null);
        }
    }

    private NewbornAlertRules() {
    }

    public static List<Finding> evaluate(Reading reading, DangerSigns signs) {
        List<Finding> findings = evaluate(reading);
        dangerSign(signs.poorFeeding(), "newborn-danger-poor-feeding", findings);
        dangerSign(signs.convulsions(), "newborn-danger-convulsions", findings);
        dangerSign(signs.severeChestIndrawing(), "newborn-danger-chest-indrawing", findings);
        dangerSign(signs.absentSpontaneousMovement(), "newborn-danger-no-movement", findings);
        return findings;
    }

    /** Each sign stands alone: only PRESENT raises anything, whatever the others and the vitals say. */
    private static void dangerSign(NewbornDangerSignObservation observation, String code, List<Finding> findings) {
        if (observation == NewbornDangerSignObservation.PRESENT) {
            findings.add(new Finding(NewbornAlertType.DANGER_SIGN, NewbornAlertSeverity.URGENT, code,
                NewbornFollowUpAction.PEDIATRICIAN_NOTIFICATION));
        }
    }

    public static List<Finding> evaluate(Reading reading) {
        List<Finding> findings = new ArrayList<>();
        temperature(reading.temperatureCelsius(), findings);
        heartRate(reading, findings);
        respiratory(reading, findings);
        oxygenation(reading.spo2Percent(), findings);
        glucose(reading, findings);
        checkTheValue(reading, findings);
        return findings;
    }

    /** Hours from birth to the assessment; null when no birth is on record. */
    public static Long hoursSinceBirth(LocalDateTime recordedBirth, java.time.LocalDate dateOfBirth,
                                       LocalDateTime assessmentTime) {
        LocalDateTime birth = recordedBirth;
        if (birth == null && dateOfBirth != null) {
            // A date alone: its last moment, so a baby still inside 72 h is never read as older.
            birth = dateOfBirth.plusDays(1).atStartOfDay();
        }
        if (birth == null || assessmentTime == null) {
            return null;
        }
        return Math.max(0, Duration.between(birth.atOffset(java.time.ZoneOffset.UTC), assessmentTime.atOffset(java.time.ZoneOffset.UTC)).toHours());
    }

    public static boolean heartRateOfConcern(Integer heartRate) {
        return heartRate != null && (heartRate >= CONCERN_HEART_RATE_HIGH || heartRate < CONCERN_HEART_RATE_LOW);
    }

    private static void temperature(Double temperature, List<Finding> findings) {
        if (temperature == null) {
            return;
        }
        if (temperature < NICE_INFECTION_TEMPERATURE_LOW || temperature >= NICE_INFECTION_TEMPERATURE_HIGH) {
            findings.add(new Finding(NewbornAlertType.THERMOREGULATION, NewbornAlertSeverity.URGENT,
                "newborn-temperature-critical", NewbornFollowUpAction.THERMAL_SUPPORT));
        } else if (temperature > WHO_DANGER_TEMPERATURE_ABOVE) {
            findings.add(new Finding(NewbornAlertType.THERMOREGULATION, NewbornAlertSeverity.URGENT,
                "newborn-temperature-who-danger", NewbornFollowUpAction.PEDIATRICIAN_NOTIFICATION));
        } else if (temperature < CAUTION_TEMPERATURE_LOW) {
            findings.add(Finding.of(NewbornAlertType.THERMOREGULATION, NewbornAlertSeverity.CAUTION, "newborn-temperature"));
        }
    }

    private static void heartRate(Reading reading, List<Finding> findings) {
        Integer heartRate = reading.heartRateBpm();
        if (heartRate == null) {
            return;
        }
        if (heartRateOfConcern(heartRate)) {
            // NICE asks for a persistent rate. One reading cannot show that, so the first one
            // alerts and asks for a recheck; only a previous reading of the same baby says more.
            findings.add(new Finding(NewbornAlertType.CARDIOVASCULAR, NewbornAlertSeverity.URGENT,
                reading.previousHeartRateOfConcern() ? "newborn-heart-rate-repeated" : "newborn-heart-rate",
                NewbornFollowUpAction.NICU_CONSULT));
        }
        if (heartRate < REFERENCE_HEART_RATE_LOW || heartRate > REFERENCE_HEART_RATE_HIGH) {
            findings.add(referenceNotice(NewbornAlertType.CARDIOVASCULAR, "newborn-reference-heart-rate", reading.preterm()));
        }
    }

    private static void respiratory(Reading reading, List<Finding> findings) {
        Integer respirations = reading.respirationsPerMin();
        if (respirations == null) {
            return;
        }
        if (respirations >= CONCERN_RESPIRATORY_HIGH || respirations < RESPIRATORY_LOW) {
            findings.add(new Finding(NewbornAlertType.RESPIRATORY, NewbornAlertSeverity.URGENT, "newborn-respiratory",
                NewbornFollowUpAction.RESPIRATORY_SUPPORT));
        }
        if (respirations < REFERENCE_RESPIRATORY_LOW || respirations > REFERENCE_RESPIRATORY_HIGH) {
            findings.add(referenceNotice(NewbornAlertType.RESPIRATORY, "newborn-reference-respiratory", reading.preterm()));
        }
    }

    private static Finding referenceNotice(NewbornAlertType type, String code, boolean preterm) {
        return Finding.of(type, NewbornAlertSeverity.INFO, preterm ? code + "-preterm" : code);
    }

    private static void oxygenation(Integer spo2, List<Finding> findings) {
        if (spo2 != null && spo2 < CONCERN_SPO2) {
            findings.add(new Finding(NewbornAlertType.OXYGENATION, NewbornAlertSeverity.URGENT, "newborn-oxygenation",
                NewbornFollowUpAction.OXYGEN_THERAPY));
        }
    }

    private static void glucose(Reading reading, List<Finding> findings) {
        Integer mgDl = reading.glucoseMgDl();
        if (mgDl == null) {
            return;
        }
        if (exactMgDl(reading) < GLUCOSE_CRITICAL_MG_DL) {
            findings.add(new Finding(NewbornAlertType.GLUCOSE, NewbornAlertSeverity.URGENT, "newborn-glucose",
                NewbornFollowUpAction.GLUCOSE_MONITORING));
            return;
        }
        Long hours = reading.hoursSinceBirth();
        if (hours == null || hours < GLUCOSE_HOURS_FIRST_PERIOD) {
            if (below(reading, GLUCOSE_FIRST_PERIOD_MMOL, GLUCOSE_FIRST_PERIOD_MG_DL)) {
                findings.add(new Finding(NewbornAlertType.GLUCOSE, NewbornAlertSeverity.URGENT,
                    hours == null ? "newborn-glucose-unknown-age" : "newborn-glucose-first-72h",
                    NewbornFollowUpAction.GLUCOSE_MONITORING));
            }
        } else if (below(reading, GLUCOSE_AFTER_MMOL, GLUCOSE_AFTER_MG_DL)) {
            findings.add(new Finding(NewbornAlertType.GLUCOSE, NewbornAlertSeverity.CAUTION, "newborn-glucose-after-72h",
                NewbornFollowUpAction.GLUCOSE_MONITORING));
        }
    }

    /** In the unit the value was entered in: 2.59 mmol/L rounds to 47 mg/dL and is still below 2.6. */
    private static boolean below(Reading reading, double mmol, int mgDl) {
        return reading.enteredMmol() != null ? reading.enteredMmol() < mmol : exactMgDl(reading) < mgDl;
    }

    /** The glucose before rounding: 46.6 mg/dL and 2.49 mmol/L are below what they round to. */
    private static double exactMgDl(Reading reading) {
        if (reading.enteredMmol() != null) {
            return GlucoseUnit.MMOL_L.toExactMgDl(reading.enteredMmol());
        }
        return reading.enteredMgDl() != null ? reading.enteredMgDl().doubleValue() : reading.glucoseMgDl().doubleValue();
    }

    /**
     * Outside what every other vitals form accepts, at either end (QA D1482): saved, with a flag
     * asking to remeasure. The numbers only choose when to ask; none of them refuses anything here.
     */
    private static void checkTheValue(Reading reading, List<Finding> findings) {
        if (reading.temperatureCelsius() != null
            && (reading.temperatureCelsius() > Double.parseDouble(VitalSignLimits.TEMPERATURE_MAX_C)
                || reading.temperatureCelsius() < Double.parseDouble(VitalSignLimits.TEMPERATURE_MIN_C))) {
            findings.add(Finding.of(NewbornAlertType.THERMOREGULATION, NewbornAlertSeverity.CAUTION, "newborn-check-temperature"));
        }
        check(reading.heartRateBpm(), VitalSignLimits.HEART_RATE_MIN, VitalSignLimits.HEART_RATE_MAX, NewbornAlertType.CARDIOVASCULAR, "newborn-check-heart-rate", findings);
        check(reading.respirationsPerMin(), VitalSignLimits.RESPIRATORY_RATE_MIN, VitalSignLimits.RESPIRATORY_RATE_MAX, NewbornAlertType.RESPIRATORY, "newborn-check-respiratory", findings);
        check(reading.glucoseMgDl(), VitalSignLimits.GLUCOSE_MIN, VitalSignLimits.GLUCOSE_MAX, NewbornAlertType.GLUCOSE, "newborn-check-glucose", findings);
        check(reading.systolicBpMmHg(), VitalSignLimits.SYSTOLIC_MIN, VitalSignLimits.SYSTOLIC_MAX, NewbornAlertType.CARDIOVASCULAR, "newborn-check-pressure", findings);
        if (reading.systolicBpMmHg() == null || (reading.systolicBpMmHg() <= VitalSignLimits.SYSTOLIC_MAX
                && reading.systolicBpMmHg() >= VitalSignLimits.SYSTOLIC_MIN)) {
            check(reading.diastolicBpMmHg(), VitalSignLimits.DIASTOLIC_MIN, VitalSignLimits.DIASTOLIC_MAX, NewbornAlertType.CARDIOVASCULAR, "newborn-check-pressure", findings);
        }
    }

    private static void check(Integer value, int floor, int ceiling, NewbornAlertType type, String code, List<Finding> findings) {
        if (value != null && (value > ceiling || value < floor)) {
            findings.add(Finding.of(type, NewbornAlertSeverity.CAUTION, code));
        }
    }
}
