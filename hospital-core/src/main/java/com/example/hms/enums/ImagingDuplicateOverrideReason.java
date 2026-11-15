package com.example.hms.enums;

/**
 * Provisional rule 2 (user decision 2026-11-15, pending the radiology
 * approver; docs/qa/regles-cliniques-provisoires.md): why an imaging order
 * was placed despite the duplicate warning (« Commander quand même »). A short
 * fixed list, as in the repeat-imaging decision-support studies where a
 * required justification from a predefined menu prevented about one repeat
 * CT in thirteen.
 */
public enum ImagingDuplicateOverrideReason {
    /** Changement clinique. */
    CLINICAL_CHANGE,
    /** Examen précédent inadéquat ou indisponible. */
    PRIOR_INADEQUATE_OR_UNAVAILABLE,
    /** Région ou question clinique différente. */
    DIFFERENT_REGION_OR_QUESTION,
    /** Autre: the note is required. */
    OTHER
}
