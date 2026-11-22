package com.example.hms.service.support;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.enums.VitalUrgencyLevel;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * QA D3351 (r70). PROVISIONAL rule set TRANSFUSION_POSITIVE_ANTIBODY_SCREEN, from the research report
 * « Block routine blood, escalate critical results » (reports/Transfusion and critical result cover.md, section 1).
 * It is « provisoire » until a named biologist or transfusion physician of the hospital signs it off; the owner is
 * not a clinician and no Burkina Faso text was found, so French rules are applied by inference.
 *
 * <p><b>The flag.</b> A positive antibody screen (RAI), or an antibody a biologist recorded, makes the patient
 * allo-immunised for good: « une obligation réglementaire en cas de transfusion chez un patient ayant une RAI
 * positive ou un antécédent de RAI positive » (HAS 2014). A later negative screen never clears it.
 *
 * <p><b>Routine and planned red cells</b> (an URGENT request, the French « urgence relative », included) leave the
 * laboratory only when all of this is on record, and nobody overrides it, a super-admin included:
 * <ol>
 *   <li>the antibody is identified by the biologist, or, where identification is not available locally and the
 *       patient cannot wait, the biologist recorded the referral to a named centre (CNTS/CRTS) and the reason for
 *       serving THIS request before the answer (North Bristol NHS Trust: units chosen on the best-predicted
 *       specificity);</li>
 *   <li>the unit is attested negative for the target antigen and RH-KEL1 matched;</li>
 *   <li>a compatible crossmatch by the indirect antiglobulin technique (BSH; the French « épreuve directe de
 *       compatibilité »), on a sample of {@value #SAMPLE_MAX_AGE_HOURS} hours or less (HAS 2014: RAI validity
 *       72 h, and a positive RAI is never extended to 21 days).</li>
 * </ol>
 *
 * <p><b>A life-threatening emergency</b> (UVI or UV, never « urgence relative ») is not delayed: « ne pas retarder
 * la transfusion même si RAI positives » (ARS Centre-Val de Loire 2022). The prescriber declares the level and a
 * reason on the request and that the sample is drawn before the first unit; the existing emergency release of
 * group O ({@link EmergencyReleasePolicy}) then applies, the biologist gets the task of the crossmatch afterwards,
 * the release carries the haemolysis warning, and the bedside check stays mandatory. A request raised before the
 * screen turned positive could not carry the declaration: it keeps the emergency path, under the existing
 * release rules, and the crossmatch afterwards is asked for all the same.
 *
 * <p>Sources as cited by the report: HAS 2014 (Transfusion de globules rouges homologues, argumentaire); ARS
 * Centre-Val de Loire 2022; BSH pre-transfusion compatibility guidelines; Australian Red Cross Lifeblood; Canadian
 * Blood Services; North Bristol NHS Trust (urgent release with antibodies present); SHOT (IT errors).
 */
@ProvisionalRule(ClinicalRuleSetKey.TRANSFUSION_POSITIVE_ANTIBODY_SCREEN)
public final class PositiveAntibodyScreenPolicy {

    /** The oldest sample a crossmatch for an allo-immunised patient may be done on. */
    public static final int SAMPLE_MAX_AGE_HOURS = 72;

    /**
     * Who records the antibody and decides on a referral: the hospital's biologist. The report also names a
     * transfusion physician; the model has no such role, so it is the biologist alone (the narrower reading).
     * Held at the hospital by assignment: a super-admin alone is never one.
     */
    public static final Set<String> BIOLOGIST_ROLE_CODES = Set.of(SecurityConstants.ROLE_LAB_DIRECTOR);

    private PositiveAntibodyScreenPolicy() {
    }

    /** The rule is about red cells: packed red cells and whole blood. Plasma and platelets are not held by it. */
    public static boolean appliesTo(BloodProductType product) {
        return product == BloodProductType.PACKED_RED_CELLS || product == BloodProductType.WHOLE_BLOOD;
    }

    /** What a crossmatch says about the unit and the sample, as the laboratory attested it. */
    public record Compatibilisation(Boolean antiglobulinMethod, Boolean antigenNegative, Boolean rhKellMatched,
                                    LocalDateTime sampleCollectedAt) {
    }

    /** The first thing a routine issue still lacks, in the order the laboratory supplies them. */
    public enum Missing {
        /** No identification, and no referral decision for this request. */
        WORKUP("transfusion.antibody.workupRequired", "bloodUnitId"),
        ANTIGLOBULIN("transfusion.antibody.antiglobulinRequired", "antiglobulinMethod"),
        ANTIGEN_NEGATIVE("transfusion.antibody.antigenNegativeRequired", "antigenNegative"),
        RH_KELL("transfusion.antibody.rhKellRequired", "rhKellMatched"),
        SAMPLE_TIME("transfusion.antibody.sampleTimeRequired", "sampleCollectedAt"),
        SAMPLE_IN_FUTURE("transfusion.antibody.sampleInFuture", "sampleCollectedAt"),
        SAMPLE_TOO_OLD("transfusion.antibody.sampleTooOld", "sampleCollectedAt");

        private final String messageKey;
        private final String field;

        Missing(String messageKey, String field) {
            this.messageKey = messageKey;
            this.field = field;
        }

        public String messageKey() {
            return messageKey;
        }

        public String field() {
            return field;
        }
    }

    /**
     * @param workupOnFile the antibody is identified, or a referral decision serves this request
     * @param crossmatch   the laboratory's attestations; null when there is no crossmatch
     * @param now          the moment the unit would leave, or the result is written
     * @return what is missing for a routine issue to an allo-immunised patient, or empty
     */
    public static Optional<Missing> firstMissing(boolean workupOnFile, Compatibilisation crossmatch, LocalDateTime now) {
        if (!workupOnFile) {
            return Optional.of(Missing.WORKUP);
        }
        if (crossmatch == null || !Boolean.TRUE.equals(crossmatch.antiglobulinMethod())) {
            return Optional.of(Missing.ANTIGLOBULIN);
        }
        if (!Boolean.TRUE.equals(crossmatch.antigenNegative())) {
            return Optional.of(Missing.ANTIGEN_NEGATIVE);
        }
        if (!Boolean.TRUE.equals(crossmatch.rhKellMatched())) {
            return Optional.of(Missing.RH_KELL);
        }
        LocalDateTime sample = crossmatch.sampleCollectedAt();
        if (sample == null) {
            return Optional.of(Missing.SAMPLE_TIME);
        }
        if (sample.isAfter(now)) {
            return Optional.of(Missing.SAMPLE_IN_FUTURE);
        }
        return sample.isBefore(now.minusHours(SAMPLE_MAX_AGE_HOURS)) ? Optional.of(Missing.SAMPLE_TOO_OLD) : Optional.empty();
    }

    /**
     * The declaration a new EMERGENCY request carries for a patient known allo-immunised: a vital urgency level
     * (UVI or UV), a reason, and that the pre-transfusion sample is drawn. An URGENT request (« urgence relative »)
     * never qualifies, whatever it carries.
     */
    public static boolean emergencyDeclared(TransfusionUrgency urgency, VitalUrgencyLevel level, String reason,
                                            Boolean sampleDrawn) {
        return urgency == TransfusionUrgency.EMERGENCY && level != null && reason != null && !reason.isBlank()
            && Boolean.TRUE.equals(sampleDrawn);
    }
}
