package com.example.hms.enums;

/**
 * Status lifecycle for patient-initiated medication refill requests.
 */
public enum RefillStatus {
    /** Patient submitted the request; awaiting provider review. */
    REQUESTED,
    /**
     * Provider deferred the decision — typically pending a visit, lab result or
     * medication review. Still actionable: a paused request can later be
     * approved or denied, and the patient may still cancel it.
     */
    PAUSED,
    /** Provider approved the refill. */
    APPROVED,
    /** Provider denied the refill (e.g. medication discontinued). */
    DENIED,
    /** Pharmacy has dispensed the medication. */
    DISPENSED,
    /** Patient cancelled the request before it was processed. */
    CANCELLED
}
