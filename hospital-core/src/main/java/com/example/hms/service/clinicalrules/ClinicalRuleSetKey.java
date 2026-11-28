package com.example.hms.service.clinicalrules;

/**
 * The provisional rule sets, one constant per entry of {@link ClinicalRuleRegistry}. The name is stored in
 * {@code hospital.hospital_rule_signoffs.rule_key} (V356): never rename a constant, add a new one.
 */
public enum ClinicalRuleSetKey {
    BLOOD_EMERGENCY_RELEASE(ClinicalRuleArea.LAB),
    NEWBORN_VITALS(ClinicalRuleArea.MATERNITY),
    PARACETAMOL_LIMITS(ClinicalRuleArea.PRESCRIBING),
    PAEDIATRIC_DOSE_LIMITS(ClinicalRuleArea.PRESCRIBING),
    AL_WHO_WEIGHT_BANDS(ClinicalRuleArea.PRESCRIBING),
    PERINATAL_CLASSIFICATION(ClinicalRuleArea.DEATH),
    STILLBIRTH_COUNTING(ClinicalRuleArea.DEATH),
    STILLBIRTH_CORRECTION(ClinicalRuleArea.DEATH),
    STILLBORN_NEONATAL_ALERTS(ClinicalRuleArea.DEATH),
    DEATH_RECORD_DISPUTE(ClinicalRuleArea.DEATH),
    ORDER_REVIEW_HOLD(ClinicalRuleArea.PRESCRIBING),
    MIDWIFE_LABOUR_ADMISSION(ClinicalRuleArea.MATERNITY),
    ALLERGY_OVERRIDE_REASON(ClinicalRuleArea.PRESCRIBING),
    ALLERGY_CONFLICT_REVIEW(ClinicalRuleArea.PRESCRIBING),
    LABOUR_TRANSFER(ClinicalRuleArea.MATERNITY),
    UNSIGNED_NOTE_WORKFLOW(ClinicalRuleArea.RECORDS),
    SEVERE_HYPERTENSION_OBSTETRIC(ClinicalRuleArea.MATERNITY),
    SEVERE_HYPERTENSION_ADULT(ClinicalRuleArea.VITALS),
    MINOR_LABOUR_SAFEGUARDING(ClinicalRuleArea.MATERNITY),
    IMAGING_URGENT_PREAUTHORISATION(ClinicalRuleArea.IMAGING),
    IMAGING_DUPLICATE_OVERRIDE(ClinicalRuleArea.IMAGING),
    SENSITIVE_CATEGORIES(ClinicalRuleArea.RECORDS),
    MINOR_HIV_RESULT_RELEASE(ClinicalRuleArea.RECORDS),
    ADOLESCENT_CONFIDENTIALITY_AGE(ClinicalRuleArea.RECORDS),
    RECOMMENDED_SENSITIVE_LAB_TESTS(ClinicalRuleArea.RECORDS),
    MIDWIFE_NEWBORN_CARE(ClinicalRuleArea.MATERNITY),
    IMAGING_TECHNICIAN_SCOPE(ClinicalRuleArea.IMAGING),
    NON_PHYSICIAN_ULTRASOUND(ClinicalRuleArea.IMAGING),
    PRESCRIPTION_RENEWAL(ClinicalRuleArea.PRESCRIBING),
    DEFERRED_BILLING(ClinicalRuleArea.BILLING),
    CONSULTATION_FEE(ClinicalRuleArea.BILLING),
    EMERGENCY_EPISODE_CONSULTATION(ClinicalRuleArea.BILLING),
    MATERNAL_EARLY_WARNING(ClinicalRuleArea.MATERNITY),
    MATERNAL_EWS_ESCALATION(ClinicalRuleArea.MATERNITY),
    CRADLE_SHOCK_INDEX(ClinicalRuleArea.MATERNITY),
    LABOUR_ALERT_VALUES(ClinicalRuleArea.MATERNITY),
    MEWS_PAGE_COOL_DOWN(ClinicalRuleArea.MATERNITY),
    TAKE_HOME_ORDER_WINDOW(ClinicalRuleArea.PRESCRIBING),
    PREGNANCY_REVEALING_IMAGING(ClinicalRuleArea.RECORDS),
    LAB_DUPLICATE_WINDOW(ClinicalRuleArea.LAB),
    OBSTETRIC_ELIGIBILITY(ClinicalRuleArea.MATERNITY),
    CLOCK_TIME_FREQUENCIES(ClinicalRuleArea.PRESCRIBING),
    LEGACY_ULTRASOUND_REPORTS(ClinicalRuleArea.IMAGING),
    SAME_DAY_PRICE_CORRECTION(ClinicalRuleArea.BILLING),
    TRANSFUSION_POSITIVE_ANTIBODY_SCREEN(ClinicalRuleArea.LAB),
    CRITICAL_RESULT_ESCALATION(ClinicalRuleArea.LAB),
    /** QA D3940 (r77): the product dispensed is the prescribed one, or a substitute of the same ingredient and strength. */
    DISPENSED_PRODUCT_SUBSTITUTION(ClinicalRuleArea.PRESCRIBING),
    /** Owner decision 2026-11-25 (V380): who decides an emergency O RhD positive release, by patient tier. */
    EMERGENCY_O_POSITIVE_AUTHORITY(ClinicalRuleArea.LAB),
    /** Owner decision 2026-11-26 (V381): who authorises a release despite an incompatible crossmatch, and the fallback. */
    INCOMPATIBLE_RELEASE_AUTHORITY(ClinicalRuleArea.LAB),
    /** QA D3862 (r77): who acknowledges an adult HIGH NEWS2, and who the reminders reach. */
    NEWS2_ESCALATION(ClinicalRuleArea.VITALS),
    /** Go-live D2 (V385): who approves a formulary entry and a dose limit, and that nobody approves their own. */
    CATALOGUE_CHANGE_APPROVAL(ClinicalRuleArea.PRESCRIBING),
    /** Scorecard K4 (V386): the one escalation ladder of every urgent clinical alert that is not a laboratory value. */
    URGENT_ALERT_ESCALATION(ClinicalRuleArea.VITALS),
    /** QA D4269 (r81), owner decision 2026-11-27: what a minor may do alone on their own portal account, by age. */
    MINOR_PORTAL_RIGHTS(ClinicalRuleArea.RECORDS),
    /** Review of #1380 (QA r81 D4181): an unpriced fill leaves only on an urgency override or an open stay. */
    UNPRICED_FILL_RELEASE(ClinicalRuleArea.BILLING);

    private final ClinicalRuleArea area;

    ClinicalRuleSetKey(ClinicalRuleArea area) {
        this.area = area;
    }

    /** The part of the product a reader knows this rule set by (the screen shows it instead of class names). */
    public ClinicalRuleArea area() {
        return area;
    }
}
