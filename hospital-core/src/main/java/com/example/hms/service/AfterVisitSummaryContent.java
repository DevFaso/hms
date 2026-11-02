package com.example.hms.service;

import com.example.hms.exception.FieldBusinessException;

import java.util.List;

/**
 * QA D1246 (round 25), as in real hospitals: the after-visit summary a
 * checkout hands the patient states at least a discharge diagnosis or the
 * patient's instructions. "Terminer la sortie" with every field empty closed
 * the visit and sent the patient an empty summary. The one rule every path
 * that ends an outpatient visit with a summary goes through (the dedicated
 * checkout and the update that sets COMPLETED).
 */
public final class AfterVisitSummaryContent {

    /** The field the refusal names: the form marks the diagnosis box (the instructions satisfy it too). */
    public static final String FIELD = "dischargeDiagnoses";

    public static final String REQUIRED_KEY = "encounter.checkout.summaryContentRequired";

    private AfterVisitSummaryContent() {
    }

    /** Refuses a summary with neither a non-blank instruction nor a non-blank diagnosis. */
    public static void require(String followUpInstructions, List<String> dischargeDiagnoses) {
        boolean hasInstructions = followUpInstructions != null && !followUpInstructions.isBlank();
        boolean hasDiagnosis = dischargeDiagnoses != null
            && dischargeDiagnoses.stream().anyMatch(d -> d != null && !d.isBlank());
        if (!hasInstructions && !hasDiagnosis) {
            throw new FieldBusinessException(FIELD, REQUIRED_KEY);
        }
    }
}
