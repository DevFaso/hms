package com.example.hms.service.lab;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.service.LabQcStatistics;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;

/**
 * QA D4147 (r81), PROVISIONAL: a control more than 3 SD from the laboratory director's target is rejected.
 *
 * <p>The operator decided alone whether a run was accepted, whatever its distance from the target: a
 * control 9 SD out was stored as accepted, counted as passed, and held no result. The 1-3s rule of
 * Westgard is the one rejection rule every laboratory applies: a single control beyond 3 SD rejects
 * the run. Such a run is recorded as rejected; the results of the test are then held until a
 * conforming run, or released one by one with the written override the release gate already asks.
 * A run between 2 and 3 SD stays a warning the operator decides on, as before. With no target there
 * is no distance, and the operator's decision stands.
 */
@ProvisionalRule(ClinicalRuleSetKey.LAB_QC_REJECTION)
public final class LabQcAcceptanceRule {

    /** The refusal of an acceptance the rule excludes, on the decision field. */
    public static final String REFUSAL_KEY = "lab.qc.outside3SdNotAccepted";
    static final String FIELD_ACCEPTED = "accepted";

    private LabQcAcceptanceRule() {
    }

    /** The run is out by rule: nobody accepts it. */
    public static boolean rejectedByRule(String sdFlag) {
        return LabQcStatistics.OUTSIDE_3SD.equals(sdFlag);
    }

    /** Refuses the acceptance of a run the rule rejects, saying how to record it instead. */
    public static void requireAcceptable(Boolean accepted, String sdFlag) {
        if (Boolean.TRUE.equals(accepted) && rejectedByRule(sdFlag)) {
            throw new FieldBusinessException(FIELD_ACCEPTED, REFUSAL_KEY);
        }
    }
}
