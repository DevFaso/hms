package com.example.hms.service.vitals;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Policy 2026-11-20 (owner: "make them a rule"): the ONE rule set of the maternal early warning. Every
 * threshold the score, the add-on alerts and the labour alerts compare a value with lives here, with
 * its source; {@code MaternalEarlyWarningRulesGuardTest} keeps it that way. Each value can be changed
 * without a release under {@code hms.maternity.ews.*} (for one, when the named clinician signs off a
 * different band).
 *
 * <p><b>PROVISIONAL</b>: every value below is a published one, adopted as the report "Burkina clinic
 * policy decisions" (section 5) recommends, pending the sign-off of a named obstetrician or
 * sage-femme. None of it is clinically approved, and every surface that shows it says « provisoire ».
 * No West African national maternal chart exists; the predictive performance of the national MEWS
 * was never tested (Gerry 2024) and labour was excluded from its derivation.
 *
 * <p>Sources:
 * <ul>
 *   <li><b>MEWS</b>: NHS England, "National MEWS – Final version" (2024), the official chart
 *       (portal.e-lfh.org.uk/ContentServer/content/MEW_01_001/course/en/assets/National-MEWS-Final-version.pdf);
 *       derivation: Gerry S et al., BMJ Medicine 2024;3(1):e000748, doi:10.1136/bmjmed-2023-000748.
 *       The temperature bands are the CHART's, which differ slightly from the paper's Table 2.</li>
 *   <li><b>CRADLE</b>: Vousden N et al., BMC Pregnancy and Childbirth 2018;18:115,
 *       doi:10.1186/s12884-018-1737-x (CRADLE Vital Signs Alert, Table 1); the shock index after
 *       Nathan et al., BJOG 2015. The red blood-pressure band (systolic or diastolic in the severe
 *       range) is the shared {@link SevereHypertension} obstetric rule and is NOT repeated here.</li>
 *   <li><b>Labour</b>: WHO Labour Care Guide (2020), section 4 "Well-being of the woman", the
 *       "Alert" column (WHO Labour Care Guide user's manual, ISBN 9789240017566).</li>
 *   <li><b>NEWS2 not used</b>: RCP, NEWS2 final report (2017), recommendation 2 and FAQ 7.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "hms.maternity.ews")
@Getter
@Setter
@ProvisionalRule({ClinicalRuleSetKey.MATERNAL_EARLY_WARNING, ClinicalRuleSetKey.MATERNAL_EWS_ESCALATION, ClinicalRuleSetKey.CRADLE_SHOCK_INDEX, ClinicalRuleSetKey.LABOUR_ALERT_VALUES, ClinicalRuleSetKey.MEWS_PAGE_COOL_DOWN})
public class MaternalEarlyWarningRules {

    /**
     * Off, the maternal score is not computed anywhere and every surface is as it was before this
     * policy: « NEWS2 non validé en grossesse / post-partum » with the NEWS2 page at its usual trigger
     * (#1237), the partograph's earlier maternal alerts ({@link LabourBeforePolicy}), no shock-index
     * alert, and a girl under 16 read as a child before her obstetric record is looked at.
     */
    private boolean enabled = true;

    private Mews mews = new Mews();
    private Cradle cradle = new Cradle();
    private Labour labour = new Labour();
    private LabourBeforePolicy labourBeforePolicy = new LabourBeforePolicy();
    private Escalation escalation = new Escalation();

    /**
     * One vital sign's bands on the chart: 2 at or below, 1 at or below, 1 from, 2 from (every edge
     * inclusive, as printed). A side the chart does not score is null.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Band {
        private Double twoAtOrBelow;
        private Double oneAtOrBelow;
        private Double oneFrom;
        private Double twoFrom;

        public Band(Double twoAtOrBelow, Double oneAtOrBelow, Double oneFrom, Double twoFrom) {
            this.twoAtOrBelow = twoAtOrBelow;
            this.oneAtOrBelow = oneAtOrBelow;
            this.oneFrom = oneFrom;
            this.twoFrom = twoFrom;
        }

        /** 0, 1 or 2 for {@code value}. */
        public int points(double value) {
            if (twoAtOrBelow != null && value <= twoAtOrBelow) {
                return 2;
            }
            if (twoFrom != null && value >= twoFrom) {
                return 2;
            }
            if (oneAtOrBelow != null && value <= oneAtOrBelow) {
                return 1;
            }
            if (oneFrom != null && value >= oneFrom) {
                return 1;
            }
            return 0;
        }
    }

    /** NHS England national MEWS (2024): six vital signs scored 0/1/2, total 0 to 12. */
    @Getter
    @Setter
    public static class Mews {
        /** Respiratory rate (breaths/min): 2 at 6 or less, 1 at 7–8, 0 at 9–21, 1 at 22–24, 2 at 25 or more. */
        private Band respiratoryRate = new Band(6.0, 8.0, 22.0, 25.0);
        /** SpO2 (%): 2 at 92 or less, 1 at 93–94, 0 at 95 or more. No high band. */
        private Band spo2 = new Band(92.0, 94.0, null, null);
        /** Temperature (°C), the chart's bands: 2 at 35.6 or less, 1 at 35.7–36.1, 0 at 36.2–37.2, 1 at 37.3–37.4, 2 at 37.5 or more. */
        private Band temperature = new Band(35.6, 36.1, 37.3, 37.5);
        /** Pulse (bpm), antenatal and up to 48 h after the birth: 2 at 62 or less, 1 at 63–70, 0 at 71–112, 1 at 113–121, 2 at 122 or more. */
        private Band pulse = new Band(62.0, 70.0, 113.0, 122.0);
        /** Pulse (bpm), from 48 h after the birth ONLY: 2 at 50 or less, 1 at 51–57, 0 at 58–98, 1 at 99–107, 2 at 108 or more. */
        private Band pulsePostBirth = new Band(50.0, 57.0, 99.0, 108.0);
        /** The post-birth pulse bands apply from this many hours after the birth. */
        private int postBirthPulseFromHours = 48;
        /** Systolic (mmHg): 2 at 93 or less, 1 at 94–100, 0 at 101–135, 1 at 136–144, 2 at 145 or more. */
        private Band systolic = new Band(93.0, 100.0, 136.0, 145.0);
        /** Diastolic (mmHg): 2 at 56 or less, 1 at 57–61, 0 at 62–88, 1 at 89–96, 2 at 97 or more. */
        private Band diastolic = new Band(56.0, 61.0, 89.0, 97.0);

        /** "Thresholds and triggers": 0–1 low, 2–4 low-medium, 5–7 medium, 8 or more high. */
        private int lowMediumFrom = 2;
        private int mediumFrom = 5;
        private int highFrom = 8;

        /** Medical review: within 30 min (low-medium), within 15 min (medium), immediate (high). */
        private int lowMediumReviewWithinMinutes = 30;
        private int mediumReviewWithinMinutes = 15;
        private int highReviewWithinMinutes = 0;

        /**
         * Minimal vital-signs recording until the review: reassess within 30 min (low-medium), within
         * 15 min (medium), continuous (high). "Continuous" is recorded as the 15-minute round the NEWS2
         * HIGH band already uses here (the shortest round the ward list sets).
         */
        private int lowMediumObservationMinutes = 30;
        private int mediumObservationMinutes = 15;
        private int highObservationMinutes = 15;

        /** One or more additional concerns: observations at least every 30 minutes. */
        private int concernObservationMinutes = 30;
        /** "Where MEWS &gt; 2", a concern raises the level of concern to the next category. */
        private int concernRaisesTierAbove = 2;
    }

    /**
     * CRADLE Vital Signs Alert add-on (the national MEWS has no single-parameter red trigger: 170/115
     * alone scores only low-medium). Shock index = heart rate ÷ systolic pressure.
     */
    @Getter
    @Setter
    public static class Cradle {
        /** Yellow (shock): shock index at or above 0.9 and below the red value. */
        private double shockIndexYellowFrom = 0.9;
        /** Red (severe shock): shock index at or above 1.7. */
        private double shockIndexRedFrom = 1.7;
    }

    /** WHO Labour Care Guide alert values for the woman during labour (each a "less than" or an "at or above"). */
    @Getter
    @Setter
    public static class Labour {
        /** Pulse: alert below 60 bpm … */
        private int pulseLowBelow = 60;
        /** … or at 120 bpm or more. */
        private int pulseHighFrom = 120;
        /** Systolic: alert below 80 mmHg … */
        private int systolicLowBelow = 80;
        /** … or at 140 mmHg or more. */
        private int systolicHighFrom = 140;
        /** Diastolic: alert at 90 mmHg or more. */
        private int diastolicHighFrom = 90;
        /** Temperature: alert below 35.0 °C … */
        private double temperatureLowBelow = 35.0;
        /** … or at 37.5 °C or more. */
        private double temperatureHighFrom = 37.5;
    }

    /**
     * The partograph's maternal alerts as they stood before this policy; they apply only when the rule
     * set is switched off, so that switching it off restores the earlier behaviour: fever from 38.0 °C,
     * pulse above 120 bpm, a raised pressure from 140 systolic or 90 diastolic, and nothing for a low
     * pressure, a slow pulse or a low temperature.
     */
    @Getter
    @Setter
    public static class LabourBeforePolicy {
        private double temperatureHighFrom = 38.0;
        private int pulseHighAbove = 120;
        private int systolicHighFrom = 140;
        private int diastolicHighFrom = 90;
    }

    /**
     * Who is paged at each level of concern. The chart names UK grades and says "staff grades are a
     * guide adaptable to local skill mix"; this is the local mapping, PROVISIONAL until the named
     * obstetric clinician confirms it:
     * <ul>
     *   <li><b>midwife in charge</b> (UK: midwife in charge) → the midwives on call now, else every
     *       midwife of the hospital ({@code midwifeInChargeRoles});</li>
     *   <li><b>doctor</b> (UK: ST1/2 or equivalent) → the stay's responsible physician and the doctors
     *       on call now, else every doctor of the hospital ({@code doctorRoles});</li>
     *   <li><b>senior doctor</b> (UK: ST3+ / consultant or equivalent) → the head of the department
     *       of the woman's stay when one is named, and the doctors on call now; every doctor of the
     *       hospital only when nobody holding the role is on the rota ({@code seniorDoctorRoles}): no
     *       senior grade is recorded on a staff member yet.</li>
     * </ul>
     * Low-medium (2–4) pages the midwife in charge and the doctor; medium (5–7) and high (8 or more)
     * page all three. The anaesthetic team and the outreach team of the UK chart are not mapped.
     *
     * <p><b>Cool-down</b> (decisions 2026-11-20, provisional): within {@code pageCoolDownMinutes} (30) of
     * a page, a page of the same level or of a LOWER one is withheld for that woman: the team already
     * holds a page at least as serious. A HIGHER level always goes through at once, and a RED add-on
     * alert (a severe shock index, a severe pressure) is NEVER withheld. A woman at the high
     * level is re-observed every 15 minutes; without it every reading would page the same circle again.
     * 0 switches the cool-down off. Only the page is withheld: the score, the chart chip, the round and
     * the recorder's on-screen warning follow every reading.
     */
    @Getter
    @Setter
    public static class Escalation {
        private List<String> midwifeInChargeRoles = List.of("ROLE_MIDWIFE");
        private List<String> doctorRoles = List.of("ROLE_DOCTOR");
        private List<String> seniorDoctorRoles = List.of("ROLE_DOCTOR");
        private int pageCoolDownMinutes = 30;
    }
}
