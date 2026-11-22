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
 * <p>The texts (title, description, approver role, EN/FR/ES) are in the {@code messages-admin} bundles under
 * {@code clinicalRules.rule.<KEY>.*}; the source citation, as given, is here.
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

    private static final List<ClinicalRuleSet> ALL = List.of(
        rule(ClinicalRuleSetKey.BLOOD_EMERGENCY_RELEASE, DATE_2026_11_07,
            "Lifeblood (emergency guidance); NZ Blood",
            "service.support.EmergencyReleasePolicy"),
        rule(ClinicalRuleSetKey.NEWBORN_VITALS, DATE_2026_11_07,
            "AHA (neonatal guidance); WHO recommendations on maternal and newborn postnatal care (2022)",
            "service.maternity.NewbornAlertRules"),
        rule(ClinicalRuleSetKey.PARACETAMOL_LIMITS, DATE_2026_11_07,
            "MSF (oral paracetamol); NHS",
            "cdshooks.rules.AdultParacetamolRule", PAEDIATRIC_DOSE_RULE),
        rule(ClinicalRuleSetKey.PAEDIATRIC_DOSE_LIMITS, DATE_2026_11_07,
            "MSF (paediatric dosing); the hospital's own protocol",
            PAEDIATRIC_DOSE_RULE),
        rule(ClinicalRuleSetKey.AL_WHO_WEIGHT_BANDS, DATE_2026_11_21,
            "WHO, Guidelines for the treatment of malaria, 3rd ed. 2015, section 4",
            "cdshooks.rules.ArtemetherLumefantrineWhoBands", PAEDIATRIC_DOSE_RULE),
        rule(ClinicalRuleSetKey.PERINATAL_CLASSIFICATION, DATE_2026_11_07,
            "WHO, ICD-11 reference guide",
            "service.support.DeathClassificationRules"),
        rule(ClinicalRuleSetKey.STILLBIRTH_COUNTING, "2026-11-13.1",
            "WHO stillbirth indicator; ICD-11",
            "service.support.StillbirthClassification"),
        rule(ClinicalRuleSetKey.STILLBIRTH_CORRECTION, DATE_2026_11_14,
            "Owner recommendation 2026-11-14 (QA D1802)",
            "service.maternity.StillbirthCertificateConflicts"),
        rule(ClinicalRuleSetKey.STILLBORN_NEONATAL_ALERTS, DATE_2026_11_14,
            "Owner recommendation 2026-11-14",
            "service.maternity.StillbornNeonatalAlerts"),
        rule(ClinicalRuleSetKey.DEATH_RECORD_DISPUTE, DATE_2026_11_07,
            "Proposed safety policy (not a universal clinical standard)",
            "service.impl.DeathDisputeServiceImpl", "service.scheduled.DeathDisputeEscalationScheduler"),
        rule(ClinicalRuleSetKey.ORDER_REVIEW_HOLD, DATE_2026_11_07,
            "WHO, Medication Without Harm (medicines reconciliation)",
            "service.impl.OrderReviewHoldServiceImpl", "service.support.OrderReviewHoldGuard"),
        rule(ClinicalRuleSetKey.MIDWIFE_LABOUR_ADMISSION, "2026-11-09.1",
            "Owner policy decision 2026-11-09",
            "service.impl.AdmissionServiceImpl"),
        rule(ClinicalRuleSetKey.ALLERGY_OVERRIDE_REASON, "2026-11-09.1",
            "Owner policy decision 2026-11-09",
            "service.PrescriptionServiceImpl"),
        rule(ClinicalRuleSetKey.ALLERGY_CONFLICT_REVIEW, "2026-11-10.1",
            "Recommendation 2026-11-10 (replaces the provisional nurse or pharmacist reason of #1108)",
            "service.allergy.CurrentAllergyRecheck"),
        rule(ClinicalRuleSetKey.LABOUR_TRANSFER, "2026-11-12.1",
            "Owner decision 2026-11-12",
            "service.impl.LaborServiceImpl"),
        rule(ClinicalRuleSetKey.UNSIGNED_NOTE_WORKFLOW, "2026-11-12.1",
            "Owner decision 2026-11-12",
            "service.UnsignedNoteFollowUp"),
        rule(ClinicalRuleSetKey.SEVERE_HYPERTENSION_OBSTETRIC, DATE_2026_11_15,
            "NICE (hypertension in pregnancy and after birth); ACOG Committee Opinion 692; ACOG 6-week postpartum care",
            SEVERE_HYPERTENSION, "cdshooks.bpa.PostpartumSevereHypertensionRule"),
        rule(ClinicalRuleSetKey.SEVERE_HYPERTENSION_ADULT, DATE_2026_11_15,
            "ACC/AHA 2017 and 2025; ESH 2023 (grade 3); NEWS2",
            SEVERE_HYPERTENSION, "cdshooks.bpa.AdultSevereHypertensionRule"),
        rule(ClinicalRuleSetKey.MINOR_LABOUR_SAFEGUARDING, DATE_2026_11_14,
            "WHO recommendations (as supplied by the owner)",
            "service.safeguarding.MinorLabourSafeguarding"),
        rule(ClinicalRuleSetKey.IMAGING_URGENT_PREAUTHORISATION, DATE_2026_11_15,
            "EMTALA (as supplied by the owner); the CNAMU rule is to be confirmed",
            "service.support.ImagingPreAuthorisationPolicy"),
        rule(ClinicalRuleSetKey.IMAGING_DUPLICATE_OVERRIDE, DATE_2026_11_15,
            "Decision-support studies on repeat imaging (as supplied by the owner)",
            "service.imaging.ImagingDuplicateOverride"),
        rule(ClinicalRuleSetKey.SENSITIVE_CATEGORIES, DATE_2026_11_20,
            "Burkina clinic policy decisions, row 4; Loi 030-2008 art. 14-18; Code pénal art. 525-1",
            PROXY_SENSITIVITY_POLICY),
        rule(ClinicalRuleSetKey.MINOR_HIV_RESULT_RELEASE, DATE_2026_11_21,
            "Loi 030-2008 art. 17-18; Code pénal art. 525-2; owner decision 2026-11-21",
            "service.recordaccess.MinorResultReleaseServiceImpl", PROXY_SENSITIVITY_POLICY),
        rule(ClinicalRuleSetKey.ADOLESCENT_CONFIDENTIALITY_AGE, DATE_2026_11_20,
            "Borrowed from international practice (no Burkinabè statute)",
            PROXY_SENSITIVITY_POLICY),
        rule(ClinicalRuleSetKey.RECOMMENDED_SENSITIVE_LAB_TESTS, RecommendedLabSensitivity.VERSION,
            "Burkina clinic policy decisions, row 4, Q4",
            "service.recordaccess.RecommendedLabSensitivity"),
        rule(ClinicalRuleSetKey.MIDWIFE_NEWBORN_CARE, DATE_2026_11_20,
            "Politique et normes SR 2010; CSP art. 65, 134; Burkina clinic policy decisions, decision 2",
            "service.maternity.MidwifeNewbornCare"),
        rule(ClinicalRuleSetKey.IMAGING_TECHNICIAN_SCOPE, DATE_2026_11_20,
            "French CSP R4351-2; IHE Scheduled Workflow; Burkina clinic policy decisions, decision 3",
            "service.imaging.ImagingRoleScope", IMAGING_EXAM_RECORD),
        rule(ClinicalRuleSetKey.NON_PHYSICIAN_ULTRASOUND, DATE_2026_11_20,
            "Burkina clinic policy decisions, decision 3; owner decision 2026-11-20",
            "service.imaging.ImagingReportingSettingsService", IMAGING_EXAM_RECORD),
        rule(ClinicalRuleSetKey.PRESCRIPTION_RENEWAL, DATE_2026_11_20,
            "Code de la santé publique du Burkina Faso (1994, consolidated 2005), art. 159, 274, 276, 279-280, 285, 287",
            "service.impl.RefillApprovalServiceImpl"),
        rule(ClinicalRuleSetKey.DEFERRED_BILLING, DATE_2026_11_20,
            "WHARC and MSH MDS-3 ch. 13 (revolving drug fund controls); « no patient turned away before assessment »",
            "service.pharmacy.DispenseBillingDeferralService"),
        rule(ClinicalRuleSetKey.CONSULTATION_FEE, DATE_2026_11_20,
            "Public tariff structure (key letters C and Cs, day of hospitalisation)",
            "service.billing.ConsultationChargeSource"),
        rule(ClinicalRuleSetKey.EMERGENCY_EPISODE_CONSULTATION, DATE_2026_11_20,
            "Owner decision 2026-11-20",
            "service.billing.EmergencyEpisodeConsultation"),
        rule(ClinicalRuleSetKey.MATERNAL_EARLY_WARNING, DATE_2026_11_20,
            "NHS England National MEWS chart (2024); Gerry et al., BMJ Medicine 2024",
            MEWS_RULES, "service.vitals.MaternalEarlyWarning", "service.vitals.News2Applicability"),
        rule(ClinicalRuleSetKey.MATERNAL_EWS_ESCALATION, DATE_2026_11_20,
            "National MEWS chart (staff grades are a guide adaptable to local skill mix)",
            MEWS_SERVICE, MEWS_RULES),
        rule(ClinicalRuleSetKey.CRADLE_SHOCK_INDEX, DATE_2026_11_20,
            "Vousden et al., BMC Pregnancy Childbirth 2018 (CRADLE Vital Signs Alert)",
            MEWS_RULES),
        rule(ClinicalRuleSetKey.LABOUR_ALERT_VALUES, DATE_2026_11_20,
            "WHO Labour Care Guide (2020), section 4",
            MEWS_RULES),
        rule(ClinicalRuleSetKey.MEWS_PAGE_COOL_DOWN, DATE_2026_11_20,
            "Owner decisions 2026-11-20 (reviews of #1241)",
            MEWS_SERVICE, MEWS_RULES),
        rule(ClinicalRuleSetKey.TAKE_HOME_ORDER_WINDOW, DATE_2026_11_21,
            "Operational choice (QA D2553)",
            "service.DischargeTakeHomeMedicines"),
        rule(ClinicalRuleSetKey.LAB_DUPLICATE_WINDOW, DATE_2026_11_21,
            "Product default (no clinical source)",
            "service.lab.LabOrderDuplicateCheck"));

    private static final Map<ClinicalRuleSetKey, ClinicalRuleSet> BY_KEY = index();

    private ClinicalRuleRegistry() {
    }

    /** Every rule set, in the order the screen and the sign-off pack list them. */
    public static List<ClinicalRuleSet> all() {
        return ALL;
    }

    public static Optional<ClinicalRuleSet> find(ClinicalRuleSetKey key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key));
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

    private static ClinicalRuleSet rule(ClinicalRuleSetKey key, String version, String source,
                                        String... enforcedIn) {
        return new ClinicalRuleSet(key, version, source, List.of(enforcedIn));
    }
}
