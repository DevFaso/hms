package com.example.hms.service.clinicalrules;

/**
 * The area of the product a rule set belongs to, as a reader names it (« Prescription », « Maternité »). The portal
 * translates it (EN/FR/ES, {@code CLINICAL_RULES.AREA.<NAME>}); class names never reach a clinician's screen.
 */
public enum ClinicalRuleArea {
    PRESCRIBING,
    MATERNITY,
    VITALS,
    DEATH,
    RECORDS,
    IMAGING,
    BILLING,
    LAB
}
