package com.example.hms.exception;

/**
 * QA D1142: the signature of an order that awaits its paediatric dose review.
 *
 * <p>Thrown by {@code PrescriptionServiceImpl.signPrescription}, which does
 * NOT roll back for it: the refusal itself is the moment an order saved
 * before the review gate learns that it needs one, and that flag
 * ({@code pediatricDoseReviewRequired}) must survive the refusal so the
 * reviewer's "Record the dose review" action appears on the order. Rolled
 * back, the flag stayed false and the UI offered no way out (round 20).
 */
public class PediatricDoseReviewRequiredException extends BusinessException {

    public PediatricDoseReviewRequiredException() {
        super("prescription.pediatricReview.requiredToSign");
    }
}
