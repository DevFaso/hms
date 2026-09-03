package com.example.hms.enums;

/**
 * The disease and care programmes a patient can be enrolled in (Tier 2 item
 * 35).
 *
 * <p>These are the six the roadmap names for this deployment — the chronic
 * conditions and the antenatal programme that national reporting (DHIS2)
 * actually asks about. The list is deliberately an enum and not a table: a
 * programme carries reporting obligations and a registry screen, so adding
 * one is a product decision, not a row an administrator types in.
 *
 * <p><b>No visit cadence lives here.</b> How often a programme patient must
 * be seen is a clinical protocol that varies by programme phase, by national
 * guideline edition and by the facility's own policy. Hardcoding "every 30
 * days" per programme would be this codebase fabricating clinical guidance —
 * the V120/WHO-data rule. The cadence is typed in per enrolment by the
 * clinician who knows the protocol; the UI requires it and suggests nothing.
 */
public enum CareProgram {
    HIV,
    TB,
    MALARIA,
    HYPERTENSION,
    DIABETES,
    ANC
}
