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

    /**
     * QA D1016/D931 class (round 18): a microbiology culture is corrected in
     * place once final (status CORRECTED, a stamped time and a mandatory
     * reason), not by a superseding row; it is the same question.
     */
    public static boolean isCorrected(com.example.hms.model.MicroCultureResult culture) {
        return culture != null && (culture.getStatus() == com.example.hms.enums.MicroCultureStatus.CORRECTED
            || culture.getCorrectedAt() != null);
    }
}
