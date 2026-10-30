package com.example.hms.service.lab;

import com.example.hms.model.LabResult;

/**
 * QA D1016 (the D931 class): whether a lab result is a correction of a result
 * the laboratory had already released.
 *
 * <p>A correction is entered as a new row that names the released row it
 * replaces ({@code supersedesResultId}); only the result-entry path writes
 * that link. Every place that shows a result's value to someone (the
 * clinician's in-basket, the chart review, the patient portal, the patient's
 * notification, the snapshot, the timeline, the PDF record, FHIR and HL7
 * outbound) asks this one question, so a reader always learns that the value
 * replaces one they may already have seen or acted on.
 * {@code LabResultCorrectionShownGuardTest} fails when a class reads a
 * result's value without asking it.
 */
public final class LabResultCorrections {

    private LabResultCorrections() {
    }

    /** True when {@code result} replaces a result that had been released. */
    public static boolean isCorrection(LabResult result) {
        return result != null && result.getSupersedesResultId() != null;
    }
}
