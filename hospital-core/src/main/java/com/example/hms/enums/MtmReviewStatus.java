package com.example.hms.enums;

/**
 * P-09: Lifecycle status for a Medication Therapy Management (MTM) review.
 *
 * <ul>
 *   <li>{@link #DRAFT} — pharmacist has started the review but not yet documented an intervention.</li>
 *   <li>{@link #COMPLETED} — intervention summary recorded and the review is closed.</li>
 *   <li>{@link #REFERRED} — handed off to a prescriber (e.g. dose change recommended).</li>
 * </ul>
 */
public enum MtmReviewStatus {
    DRAFT,
    COMPLETED,
    REFERRED
}
