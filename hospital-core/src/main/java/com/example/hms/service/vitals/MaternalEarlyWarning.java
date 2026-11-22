package com.example.hms.service.vitals;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.enums.ConsciousnessLevel;
import com.example.hms.enums.MaternalConcern;
import com.example.hms.model.PatientVitalSign;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Policy 2026-11-20 (PROVISIONAL, pending a named obstetric clinician's sign-off): the maternal early
 * warning, computed. No number is written here: every threshold comes from
 * {@link MaternalEarlyWarningRules}, which cites its sources. No query either; who is under the score
 * (pregnant, or up to 42 days after a birth) is {@link News2Applicability}'s answer.
 *
 * <ul>
 *   <li><b>Score</b> (NHS England national MEWS 2024): six vital signs 0/1/2, total 0–12; the pulse
 *       table switches to the post-birth bands 48 h after the birth. A set that lacks a vital sign is
 *       scored on what was recorded and says which are missing ("a score for each vital sign is
 *       required at each entry"): the true score can only be equal or higher.</li>
 *   <li><b>Level of concern</b>: 0–1 low, 2–4 low-medium, 5–7 medium, 8 or more high.</li>
 *   <li><b>Additional concerns</b> are flags, never points: with one or more, the level is at least
 *       low-medium, one level higher when the score is above 2, and observations are at least every
 *       30 minutes.</li>
 *   <li><b>CRADLE add-on</b>: the severe blood pressure of the shared obstetric rule is RED; a shock
 *       index (heart rate ÷ systolic) from 0.9 is YELLOW and from 1.7 RED. A RED alert is answered as
 *       the high level is (immediate review), whatever the score: the national MEWS has no
 *       single-parameter trigger, which is the gap the add-on closes.</li>
 *   <li><b>Labour</b> (WHO Labour Care Guide alert values): {@link #labourAlerts}.</li>
 * </ul>
 * Nothing here ever refuses a reading.
 */
@ProvisionalRule(ClinicalRuleSetKey.MATERNAL_EARLY_WARNING)
public final class MaternalEarlyWarning {

    private MaternalEarlyWarning() {
    }

    /** The chart's four levels of concern, lowest first. */
    public enum Tier { LOW, LOW_MEDIUM, MEDIUM, HIGH }

    /** The six scored vital signs, in the chart's order. */
    public enum Parameter { RESPIRATORY_RATE, SPO2, TEMPERATURE, PULSE, SYSTOLIC, DIASTOLIC }

    /** A CRADLE traffic light that is not green. */
    public enum Light { YELLOW, RED }

    /** A CRADLE add-on alert. */
    public enum HardAlert {
        /** Systolic or diastolic in the shared obstetric severe range. */
        SEVERE_HYPERTENSION(Light.RED),
        /** Shock index from the yellow value, below the red one. */
        SHOCK(Light.YELLOW),
        /** Shock index from the red value. */
        SEVERE_SHOCK(Light.RED);

        private final Light light;

        HardAlert(Light light) {
            this.light = light;
        }

        public Light light() {
            return light;
        }
    }

    /** A WHO Labour Care Guide alert value met during labour. */
    public enum LabourAlert {
        PULSE_LOW, PULSE_HIGH, SYSTOLIC_LOW, SYSTOLIC_HIGH, DIASTOLIC_HIGH, TEMPERATURE_LOW, TEMPERATURE_HIGH
    }

    /**
     * One vital set, scored.
     *
     * @param total          the points of the vital signs that were recorded (0–12)
     * @param points         the points of each recorded vital sign
     * @param missing        the vital signs the set lacks
     * @param postBirthPulse the pulse was scored with the bands that apply from 48 h after the birth
     * @param concerns       the additional concerns on the set
     * @param scoreTier      the level of concern of the total alone
     * @param tier           the level of concern once the additional concerns have raised it
     * @param alerts         the CRADLE add-on alerts
     * @param responseTier   the level the set is answered at: {@code tier}, or high with a RED alert
     */
    public record Score(int total, Map<Parameter, Integer> points, List<Parameter> missing, boolean postBirthPulse,
                        Set<MaternalConcern> concerns, Tier scoreTier, Tier tier, List<HardAlert> alerts,
                        Tier responseTier) {
        public Score {
            points = points == null ? Map.of() : Map.copyOf(points);
            missing = missing == null ? List.of() : List.copyOf(missing);
            concerns = concerns == null || concerns.isEmpty() ? Set.of()
                : java.util.Collections.unmodifiableSet(EnumSet.copyOf(concerns));
            alerts = alerts == null ? List.of() : List.copyOf(alerts);
        }

        public boolean complete() {
            return missing.isEmpty();
        }

        /** The set carries no scored vital sign, no concern and no alert: there is nothing to show. */
        public boolean empty() {
            return points.isEmpty() && concerns.isEmpty() && alerts.isEmpty();
        }

        /** Low-medium or more: somebody must review her. */
        public boolean needsEscalation() {
            return responseTier != Tier.LOW;
        }

        public boolean red() {
            return alerts.stream().anyMatch(a -> a.light() == Light.RED);
        }
    }

    /** The post-birth pulse bands apply to a reading taken 48 h or more after {@code birthAt}. */
    public static boolean postBirthPulse(MaternalEarlyWarningRules rules, LocalDateTime birthAt, LocalDateTime at) {
        return birthAt != null && at != null
            && !at.isBefore(birthAt.plusHours(rules.getMews().getPostBirthPulseFromHours()));
    }

    /** The whole warning for one vital set. */
    public static Score score(MaternalEarlyWarningRules rules, PatientVitalSign vital, boolean postBirthPulse) {
        MaternalEarlyWarningRules.Mews mews = rules.getMews();
        Map<Parameter, Integer> points = new EnumMap<>(Parameter.class);
        List<Parameter> missing = new ArrayList<>();
        add(points, missing, Parameter.RESPIRATORY_RATE, mews.getRespiratoryRate(), vital.getRespiratoryRateBpm());
        add(points, missing, Parameter.SPO2, mews.getSpo2(), vital.getSpo2Percent());
        // The chart is printed to one decimal: 37.25 is read as 37.3, never as "between two bands".
        Double temperature = vital.getTemperatureCelsius() == null ? null
            : Math.round(vital.getTemperatureCelsius() * 10.0) / 10.0;
        add(points, missing, Parameter.TEMPERATURE, mews.getTemperature(), temperature);
        add(points, missing, Parameter.PULSE, postBirthPulse ? mews.getPulsePostBirth() : mews.getPulse(),
            vital.getHeartRateBpm());
        add(points, missing, Parameter.SYSTOLIC, mews.getSystolic(), vital.getSystolicBpMmHg());
        add(points, missing, Parameter.DIASTOLIC, mews.getDiastolic(), vital.getDiastolicBpMmHg());
        int total = points.values().stream().mapToInt(Integer::intValue).sum();
        Set<MaternalConcern> concerns = concernsOf(vital);
        Tier scoreTier = tierOf(rules, total);
        Tier tier = raisedByConcerns(rules, scoreTier, total, !concerns.isEmpty());
        List<HardAlert> alerts = hardAlerts(rules, vital.getHeartRateBpm(), vital.getSystolicBpMmHg(),
            vital.getDiastolicBpMmHg());
        boolean red = alerts.stream().anyMatch(a -> a.light() == Light.RED);
        return new Score(total, points, missing, postBirthPulse, concerns, scoreTier, tier, alerts,
            red ? Tier.HIGH : tier);
    }

    private static void add(Map<Parameter, Integer> points, List<Parameter> missing, Parameter parameter,
                            MaternalEarlyWarningRules.Band band, Number value) {
        if (value == null) {
            missing.add(parameter);
        } else {
            points.put(parameter, band.points(value.doubleValue()));
        }
    }

    /** The level of concern of a total alone. */
    public static Tier tierOf(MaternalEarlyWarningRules rules, int total) {
        MaternalEarlyWarningRules.Mews mews = rules.getMews();
        if (total >= mews.getHighFrom()) {
            return Tier.HIGH;
        }
        if (total >= mews.getMediumFrom()) {
            return Tier.MEDIUM;
        }
        return total >= mews.getLowMediumFrom() ? Tier.LOW_MEDIUM : Tier.LOW;
    }

    /** With a concern: at least low-medium, and the next level when the total is above 2. */
    public static Tier raisedByConcerns(MaternalEarlyWarningRules rules, Tier scoreTier, int total, boolean concern) {
        if (!concern) {
            return scoreTier;
        }
        if (total > rules.getMews().getConcernRaisesTierAbove()) {
            return scoreTier == Tier.HIGH ? Tier.HIGH : Tier.values()[scoreTier.ordinal() + 1];
        }
        return scoreTier == Tier.LOW ? Tier.LOW_MEDIUM : scoreTier;
    }

    /**
     * The additional concerns of a set: the ones the recorder ticked, plus the two the set already
     * records on its own (oxygen is an additional therapy; anything but « alert » is a decreased level
     * of consciousness).
     */
    public static Set<MaternalConcern> concernsOf(PatientVitalSign vital) {
        Set<MaternalConcern> concerns = EnumSet.noneOf(MaternalConcern.class);
        concerns.addAll(MaternalConcern.parse(vital.getMaternalConcerns()));
        if (Boolean.TRUE.equals(vital.getOnOxygen())) {
            concerns.add(MaternalConcern.ADDITIONAL_THERAPY);
        }
        if (vital.getConsciousnessLevel() != null && vital.getConsciousnessLevel() != ConsciousnessLevel.ALERT) {
            concerns.add(MaternalConcern.DECREASED_CONSCIOUSNESS);
        }
        return concerns;
    }

    /** Heart rate ÷ systolic pressure; null when either is missing or the pressure is not positive. */
    public static Double shockIndex(Integer heartRate, Integer systolic) {
        if (heartRate == null || systolic == null || systolic <= 0) {
            return null;
        }
        return heartRate.doubleValue() / systolic.doubleValue();
    }

    /** The CRADLE add-on alerts of a heart rate and a blood pressure; a missing value never alerts. */
    public static List<HardAlert> hardAlerts(MaternalEarlyWarningRules rules, Integer heartRate, Integer systolic,
                                             Integer diastolic) {
        List<HardAlert> alerts = new ArrayList<>();
        // The red pressure band is the shared obstetric rule's: one place holds those two numbers.
        if (SevereHypertension.isObstetricSevere(systolic, diastolic)) {
            alerts.add(HardAlert.SEVERE_HYPERTENSION);
        }
        Double index = shockIndex(heartRate, systolic);
        if (index != null) {
            if (index >= rules.getCradle().getShockIndexRedFrom()) {
                alerts.add(HardAlert.SEVERE_SHOCK);
            } else if (index >= rules.getCradle().getShockIndexYellowFrom()) {
                alerts.add(HardAlert.SHOCK);
            }
        }
        return alerts;
    }

    /**
     * The shock alert alone (the pressure alert has its own, older, path on the maternity screens).
     * None with the rule set switched off: the maternity screens had no shock-index alert before it.
     */
    public static HardAlert shockAlert(MaternalEarlyWarningRules rules, Integer heartRate, Integer systolic) {
        if (!rules.isEnabled()) {
            return null;
        }
        return hardAlerts(rules, heartRate, systolic, null).stream()
            .filter(a -> a != HardAlert.SEVERE_HYPERTENSION).findFirst().orElse(null);
    }

    /** Medical review within this many minutes (0 = immediate); null at the low level. */
    public static Integer reviewWithinMinutes(MaternalEarlyWarningRules rules, Tier tier) {
        MaternalEarlyWarningRules.Mews mews = rules.getMews();
        return switch (tier) {
            case LOW -> null;
            case LOW_MEDIUM -> mews.getLowMediumReviewWithinMinutes();
            case MEDIUM -> mews.getMediumReviewWithinMinutes();
            case HIGH -> mews.getHighReviewWithinMinutes();
        };
    }

    /**
     * The interval to the next set of observations. Never longer than {@code routine}: the score only
     * shortens a round. A concern alone brings it to 30 minutes.
     */
    public static Duration observationInterval(MaternalEarlyWarningRules rules, Score score, Duration routine) {
        if (score == null) {
            return routine;
        }
        MaternalEarlyWarningRules.Mews mews = rules.getMews();
        Duration interval = switch (score.responseTier()) {
            case LOW -> routine;
            case LOW_MEDIUM -> Duration.ofMinutes(mews.getLowMediumObservationMinutes());
            case MEDIUM -> Duration.ofMinutes(mews.getMediumObservationMinutes());
            case HIGH -> Duration.ofMinutes(mews.getHighObservationMinutes());
        };
        if (!score.concerns().isEmpty()) {
            Duration concern = Duration.ofMinutes(mews.getConcernObservationMinutes());
            interval = concern.compareTo(interval) < 0 ? concern : interval;
        }
        return interval.compareTo(routine) < 0 ? interval : routine;
    }

    /**
     * The WHO Labour Care Guide alert values a labouring woman's reading meets; a missing value never
     * alerts. With the rule set switched off, the partograph's alerts as they stood before the policy.
     */
    public static List<LabourAlert> labourAlerts(MaternalEarlyWarningRules rules, Integer pulse, Integer systolic,
                                                 Integer diastolic, Double temperature) {
        if (!rules.isEnabled()) {
            return labourAlertsBeforePolicy(rules.getLabourBeforePolicy(), pulse, systolic, diastolic, temperature);
        }
        MaternalEarlyWarningRules.Labour labour = rules.getLabour();
        List<LabourAlert> alerts = new ArrayList<>();
        if (pulse != null && pulse < labour.getPulseLowBelow()) {
            alerts.add(LabourAlert.PULSE_LOW);
        }
        if (pulse != null && pulse >= labour.getPulseHighFrom()) {
            alerts.add(LabourAlert.PULSE_HIGH);
        }
        if (systolic != null && systolic < labour.getSystolicLowBelow()) {
            alerts.add(LabourAlert.SYSTOLIC_LOW);
        }
        if (systolic != null && systolic >= labour.getSystolicHighFrom()) {
            alerts.add(LabourAlert.SYSTOLIC_HIGH);
        }
        if (diastolic != null && diastolic >= labour.getDiastolicHighFrom()) {
            alerts.add(LabourAlert.DIASTOLIC_HIGH);
        }
        if (temperature != null && temperature < labour.getTemperatureLowBelow()) {
            alerts.add(LabourAlert.TEMPERATURE_LOW);
        }
        if (temperature != null && temperature >= labour.getTemperatureHighFrom()) {
            alerts.add(LabourAlert.TEMPERATURE_HIGH);
        }
        return alerts;
    }

    /** Before the policy: a fever, a pulse above the limit and a raised pressure; no low value alerted. */
    private static List<LabourAlert> labourAlertsBeforePolicy(MaternalEarlyWarningRules.LabourBeforePolicy before,
                                                              Integer pulse, Integer systolic, Integer diastolic,
                                                              Double temperature) {
        List<LabourAlert> alerts = new ArrayList<>();
        if (pulse != null && pulse > before.getPulseHighAbove()) {
            alerts.add(LabourAlert.PULSE_HIGH);
        }
        if (systolic != null && systolic >= before.getSystolicHighFrom()) {
            alerts.add(LabourAlert.SYSTOLIC_HIGH);
        }
        if (diastolic != null && diastolic >= before.getDiastolicHighFrom()) {
            alerts.add(LabourAlert.DIASTOLIC_HIGH);
        }
        if (temperature != null && temperature >= before.getTemperatureHighFrom()) {
            alerts.add(LabourAlert.TEMPERATURE_HIGH);
        }
        return alerts;
    }
}
