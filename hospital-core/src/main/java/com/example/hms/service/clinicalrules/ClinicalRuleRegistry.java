package com.example.hms.service.clinicalrules;

import com.example.hms.service.recordaccess.RecommendedLabSensitivity;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owner direction 2026-11-21: every clinical or operational rule built so far is « provisoire » until EACH
 * hospital's named approver signs it off at onboarding. This is the one list of those rule sets, in code: what the
 * rule does, the source it rests on (cited as given, never re-verified here), who signs it off, the version a
 * sign-off is recorded against, and the classes that enforce it. A hospital's decisions live in
 * {@code hospital.hospital_rule_signoffs} (V356), keyed by {@link ClinicalRuleSetKey} and version.
 *
 * <p>The texts (title, description, source, approver role, EN/FR/ES) are in the {@code messages-admin} bundles
 * under {@code clinicalRules.rule.<KEY>.*}: a published citation as written, a decision in the reader's words.
 *
 * <p>It mirrors the local documents {@code docs/qa/regles-cliniques-provisoires.md} and
 * {@code docs/qa/approvals-register.md}. {@code ClinicalRuleRegistryGuardTest} keeps it honest against the code:
 * every class listed here carries {@link ProvisionalRule} with the key, and every class carrying the annotation is
 * listed under each of its keys.
 *
 * <p><b>Changing a rule</b>: change its code and tests, then bump its {@link ClinicalRuleSet#version()} here. Every
 * hospital's sign-off of the old version stops counting and the rule set reads « provisoire » again until the
 * approver signs the new version.
 *
 * <p>Nothing here changes how a rule behaves: a signed rule enforces exactly as a pending one.
 */
public final class ClinicalRuleRegistry {

    private static final String MEWS_RULES = "service.vitals.MaternalEarlyWarningRules";
    private static final String MEWS_SERVICE = "service.vitals.MaternalEarlyWarningService";
    private static final String PAEDIATRIC_DOSE_RULE = "cdshooks.rules.PediatricDoseRule";
    private static final String SEVERE_HYPERTENSION = "service.vitals.SevereHypertension";
    private static final String PROXY_SENSITIVITY_POLICY = "service.recordaccess.ProxySensitivityPolicy";
    private static final String IMAGING_EXAM_RECORD = "service.support.ImagingExamRecord";
    private static final String DATE_2026_11_07 = "2026-11-07.1";
    private static final String DATE_2026_11_14 = "2026-11-14.1";
    private static final String DATE_2026_11_15 = "2026-11-15.1";
    private static final String DATE_2026_11_20 = "2026-11-20.1";
    private static final String DATE_2026_11_21 = "2026-11-21.1";
    /** QA r70, first build of the day: the child paracetamol default (D3306), maternal pages to acknowledge (D3304). */
    private static final String DATE_2026_11_22 = "2026-11-22.1";
    /**
     * The rule sets of 2026-11-22 as amended by the review of their first build, the same day (.2).
     *
     * <p>QA r70 (D3450, D3301): the imaging rule's catalogue flag and fail-closed band; who a renewal is announced to.
     * Review of #1290: the report and captions a proxy would read, a clearance only under the exam's current
     * name, an unknown body part and an implausible age held back; a prescriber who may no longer renew is covered.
     *
     * <p>QA r70 (D3351, C5), review of #1291: every positive screen asks the biologist and an emergency on a plain
     * crossmatch owes the crossmatch afterwards; SMS after the commit, the results already waiting never texted
     * about, an incident closed when its result is amended or no longer critical.
     */
    private static final String DATE_2026_11_22_REVIEWED = "2026-11-22.2";

    private static final List<ClinicalRuleSet> ALL = List.of(
        // 2026-11-25 (.1): who may receive O positive moved to EMERGENCY_O_POSITIVE_AUTHORITY; the role sets stay.
        rule(ClinicalRuleSetKey.BLOOD_EMERGENCY_RELEASE, "2026-11-25.1",
            "service.support.EmergencyReleasePolicy"),
        rule(ClinicalRuleSetKey.NEWBORN_VITALS, DATE_2026_11_07,
            "service.maternity.NewbornAlertRules"),
        // 2026-11-22 (QA D3306): the child's limits apply by default where the catalogue records none.
        rule(ClinicalRuleSetKey.PARACETAMOL_LIMITS, DATE_2026_11_22,
            "cdshooks.rules.AdultParacetamolRule", PAEDIATRIC_DOSE_RULE,
            "cdshooks.rules.ParacetamolPaediatricDefault"),
        rule(ClinicalRuleSetKey.PAEDIATRIC_DOSE_LIMITS, DATE_2026_11_07,
            PAEDIATRIC_DOSE_RULE),
        rule(ClinicalRuleSetKey.AL_WHO_WEIGHT_BANDS, DATE_2026_11_21,
            "cdshooks.rules.ArtemetherLumefantrineWhoBands", PAEDIATRIC_DOSE_RULE),
        rule(ClinicalRuleSetKey.PERINATAL_CLASSIFICATION, DATE_2026_11_07,
            "service.support.DeathClassificationRules"),
        rule(ClinicalRuleSetKey.STILLBIRTH_COUNTING, "2026-11-13.1",
            "service.support.StillbirthClassification"),
        rule(ClinicalRuleSetKey.STILLBIRTH_CORRECTION, DATE_2026_11_14,
            "service.maternity.StillbirthCertificateConflicts"),
        rule(ClinicalRuleSetKey.STILLBORN_NEONATAL_ALERTS, DATE_2026_11_14,
            "service.maternity.StillbornNeonatalAlerts"),
        rule(ClinicalRuleSetKey.DEATH_RECORD_DISPUTE, DATE_2026_11_07,
            "service.impl.DeathDisputeServiceImpl", "service.scheduled.DeathDisputeEscalationScheduler"),
        rule(ClinicalRuleSetKey.ORDER_REVIEW_HOLD, DATE_2026_11_07,
            "service.impl.OrderReviewHoldServiceImpl", "service.support.OrderReviewHoldGuard"),
        rule(ClinicalRuleSetKey.MIDWIFE_LABOUR_ADMISSION, "2026-11-09.1",
            "service.impl.AdmissionServiceImpl"),
        rule(ClinicalRuleSetKey.ALLERGY_OVERRIDE_REASON, "2026-11-09.1",
            "service.PrescriptionServiceImpl"),
        rule(ClinicalRuleSetKey.ALLERGY_CONFLICT_REVIEW, "2026-11-10.1",
            "service.allergy.CurrentAllergyRecheck"),
        rule(ClinicalRuleSetKey.LABOUR_TRANSFER, "2026-11-12.1",
            "service.impl.LaborServiceImpl"),
        rule(ClinicalRuleSetKey.UNSIGNED_NOTE_WORKFLOW, "2026-11-12.1",
            "service.UnsignedNoteFollowUp"),
        rule(ClinicalRuleSetKey.SEVERE_HYPERTENSION_OBSTETRIC, DATE_2026_11_15,
            SEVERE_HYPERTENSION, "cdshooks.bpa.PostpartumSevereHypertensionRule"),
        rule(ClinicalRuleSetKey.SEVERE_HYPERTENSION_ADULT, DATE_2026_11_15,
            SEVERE_HYPERTENSION, "cdshooks.bpa.AdultSevereHypertensionRule"),
        rule(ClinicalRuleSetKey.MINOR_LABOUR_SAFEGUARDING, DATE_2026_11_14,
            "service.safeguarding.MinorLabourSafeguarding"),
        rule(ClinicalRuleSetKey.IMAGING_URGENT_PREAUTHORISATION, DATE_2026_11_15,
            "service.support.ImagingPreAuthorisationPolicy"),
        rule(ClinicalRuleSetKey.IMAGING_DUPLICATE_OVERRIDE, DATE_2026_11_15,
            "service.imaging.ImagingDuplicateOverride"),
        rule(ClinicalRuleSetKey.SENSITIVE_CATEGORIES, DATE_2026_11_20,
            PROXY_SENSITIVITY_POLICY),
        rule(ClinicalRuleSetKey.MINOR_HIV_RESULT_RELEASE, DATE_2026_11_21,
            "service.recordaccess.MinorResultReleaseServiceImpl", PROXY_SENSITIVITY_POLICY),
        rule(ClinicalRuleSetKey.ADOLESCENT_CONFIDENTIALITY_AGE, DATE_2026_11_20,
            PROXY_SENSITIVITY_POLICY),
        rule(ClinicalRuleSetKey.RECOMMENDED_SENSITIVE_LAB_TESTS, RecommendedLabSensitivity.VERSION,
            "service.recordaccess.RecommendedLabSensitivity"),
        rule(ClinicalRuleSetKey.MIDWIFE_NEWBORN_CARE, DATE_2026_11_20,
            "service.maternity.MidwifeNewbornCare"),
        rule(ClinicalRuleSetKey.IMAGING_TECHNICIAN_SCOPE, DATE_2026_11_20,
            "service.imaging.ImagingRoleScope", IMAGING_EXAM_RECORD),
        rule(ClinicalRuleSetKey.NON_PHYSICIAN_ULTRASOUND, DATE_2026_11_20,
            "service.imaging.ImagingReportingSettingsService", IMAGING_EXAM_RECORD),
        rule(ClinicalRuleSetKey.PRESCRIPTION_RENEWAL, DATE_2026_11_22_REVIEWED,
            "service.impl.RefillApprovalServiceImpl", "service.pharmacy.RenewalAlertRouting"),
        rule(ClinicalRuleSetKey.DEFERRED_BILLING, DATE_2026_11_20,
            "service.pharmacy.DispenseBillingDeferralService"),
        rule(ClinicalRuleSetKey.CONSULTATION_FEE, DATE_2026_11_20,
            "service.billing.ConsultationChargeSource"),
        rule(ClinicalRuleSetKey.EMERGENCY_EPISODE_CONSULTATION, DATE_2026_11_20,
            "service.billing.EmergencyEpisodeConsultation"),
        rule(ClinicalRuleSetKey.MATERNAL_EARLY_WARNING, DATE_2026_11_20,
            MEWS_RULES, "service.vitals.MaternalEarlyWarning", "service.vitals.News2Applicability"),
        // 2026-11-22 (QA D3304): a medium, high or red page is also an urgent alert to acknowledge.
        rule(ClinicalRuleSetKey.MATERNAL_EWS_ESCALATION, DATE_2026_11_22,
            MEWS_SERVICE, MEWS_RULES),
        rule(ClinicalRuleSetKey.CRADLE_SHOCK_INDEX, DATE_2026_11_20,
            MEWS_RULES),
        rule(ClinicalRuleSetKey.LABOUR_ALERT_VALUES, DATE_2026_11_20,
            MEWS_RULES),
        rule(ClinicalRuleSetKey.MEWS_PAGE_COOL_DOWN, DATE_2026_11_20,
            MEWS_SERVICE, MEWS_RULES),
        rule(ClinicalRuleSetKey.TAKE_HOME_ORDER_WINDOW, DATE_2026_11_21,
            "service.DischargeTakeHomeMedicines"),
        rule(ClinicalRuleSetKey.PREGNANCY_REVEALING_IMAGING, DATE_2026_11_22_REVIEWED,
            "service.imaging.PregnancyRevealingStudyRule"),
        rule(ClinicalRuleSetKey.LAB_DUPLICATE_WINDOW, DATE_2026_11_21,
            "service.lab.LabOrderDuplicateCheck"),
        rule(ClinicalRuleSetKey.OBSTETRIC_ELIGIBILITY, DATE_2026_11_21,
            "utility.ObstetricEligibility"),
        rule(ClinicalRuleSetKey.CLOCK_TIME_FREQUENCIES, DATE_2026_11_21,
            "service.impl.FrequencyCadence", "service.impl.MarDoseSchedule", "service.impl.MarNextDose"),
        rule(ClinicalRuleSetKey.LEGACY_ULTRASOUND_REPORTS, DATE_2026_11_21,
            "service.impl.UltrasoundServiceImpl"),
        rule(ClinicalRuleSetKey.SAME_DAY_PRICE_CORRECTION, DATE_2026_11_21,
            "service.billing.PriceListService", "service.billing.PriceVersionRules"),
        // QA D3351 (r70), research report « Transfusion and critical result cover », section 1.
        rule(ClinicalRuleSetKey.TRANSFUSION_POSITIVE_ANTIBODY_SCREEN, DATE_2026_11_22_REVIEWED,
            "service.support.PositiveAntibodyScreenPolicy", "service.transfusion.PositiveAntibodyScreenService"),
        // QA r70 scorecard C5, research report « Transfusion and critical result cover », section 2.
        rule(ClinicalRuleSetKey.CRITICAL_RESULT_ESCALATION, DATE_2026_11_22_REVIEWED,
            "service.lab.CriticalResultEscalationLadder", "service.lab.CriticalResultRecipients",
            "service.lab.CriticalResultEscalationSettingsService"),
        // QA D3940 (r77): what a fill may take from the shelf against an order; substitution by ingredient and strength.
        rule(ClinicalRuleSetKey.DISPENSED_PRODUCT_SUBSTITUTION, "2026-11-25.1",
            "service.pharmacy.DispensedProductMatch", "service.pharmacy.DispenseVerificationService"),
        // Owner decision 2026-11-25, research report « Emergency O positive release authority »: who decides, by tier.
        rule(ClinicalRuleSetKey.EMERGENCY_O_POSITIVE_AUTHORITY, "2026-11-25.1",
            "service.support.OPositiveReleaseAuthority", "service.transfusion.OPositiveReleaseAuthorityService",
            "service.scheduled.OPositiveCountersignEscalationScheduler"),
        // QA D3862 (r77): a HIGH NEWS2 is an urgent alert to acknowledge (the D3304 path), not a bell alone.
        rule(ClinicalRuleSetKey.NEWS2_ESCALATION, "2026-11-25.1",
            "service.vitals.News2EscalationService", "service.vitals.News2CareTeam"));

    private static final Map<ClinicalRuleSetKey, ClinicalRuleSet> BY_KEY = index();

    /**
     * The public page of a rule set's main published source, where one exists (D3501): the approver opens it from the
     * screen and reads it printed in the sign-off pack. A source that is a platform decision or has no public page
     * has no entry; only addresses of the publisher's own catalogue pages are listed.
     */
    private static final Map<ClinicalRuleSetKey, String> SOURCE_URLS = Map.of(
        ClinicalRuleSetKey.AL_WHO_WEIGHT_BANDS, "https://www.who.int/publications/i/item/9789241549127",
        ClinicalRuleSetKey.PERINATAL_CLASSIFICATION, "https://icd.who.int/",
        ClinicalRuleSetKey.STILLBIRTH_COUNTING, "https://icd.who.int/",
        ClinicalRuleSetKey.ORDER_REVIEW_HOLD, "https://www.who.int/initiatives/medication-without-harm",
        ClinicalRuleSetKey.NEWBORN_VITALS, "https://www.who.int/publications/i/item/9789240045989",
        ClinicalRuleSetKey.LABOUR_ALERT_VALUES, "https://www.who.int/publications/i/item/9789240017566",
        ClinicalRuleSetKey.SEVERE_HYPERTENSION_OBSTETRIC, "https://www.nice.org.uk/guidance/ng133",
        // QA D3351: HAS 2014, the first source the research report cites for the rule (a public PDF).
        ClinicalRuleSetKey.TRANSFUSION_POSITIVE_ANTIBODY_SCREEN,
        "https://www.has-sante.fr/upload/docs/application/pdf/2015-02/transfusion_de_globules_rouges_homologues_-_produits_et_examens_immuno-hematologiques_-_argumentaire.pdf",
        // Scorecard C5: RCPath G158, the public guidance the research report cites for the escalation duty.
        ClinicalRuleSetKey.CRITICAL_RESULT_ESCALATION,
        "https://www.rcpath.org/asset/BB86B370-1545-4C5A-B5826A2C431934F5/");

    private ClinicalRuleRegistry() {
    }

    /** Every rule set, in the order the screen and the sign-off pack list them. */
    public static List<ClinicalRuleSet> all() {
        return ALL;
    }

    public static Optional<ClinicalRuleSet> find(ClinicalRuleSetKey key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key));
    }

    /** The public page of the rule set's main published source, or empty when it has none. */
    public static Optional<String> sourceUrl(ClinicalRuleSetKey key) {
        return Optional.ofNullable(SOURCE_URLS.get(key));
    }

    /** The rule set named {@code key} (a stored or requested name), or empty for a name nobody defines. */
    public static Optional<ClinicalRuleSet> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(ClinicalRuleSetKey.values())
            .filter(candidate -> candidate.name().equals(key))
            .findFirst()
            .flatMap(ClinicalRuleRegistry::find);
    }

    private static Map<ClinicalRuleSetKey, ClinicalRuleSet> index() {
        Map<ClinicalRuleSetKey, ClinicalRuleSet> byKey = new EnumMap<>(ClinicalRuleSetKey.class);
        for (ClinicalRuleSet rule : ALL) {
            // A duplicate key keeps its first entry; ClinicalRuleRegistryGuardTest fails on it.
            byKey.putIfAbsent(rule.key(), rule);
        }
        return byKey;
    }

    private static ClinicalRuleSet rule(ClinicalRuleSetKey key, String version, String... enforcedIn) {
        return new ClinicalRuleSet(key, version, List.of(enforcedIn));
    }
}
