package com.example.hms.validation;

/**
 * Bean-validation group of a reason that is required only when the request
 * refuses or holds something (a refill denied or put on hold), while the same
 * body may carry it optionally on approval.
 *
 * <p>QA D1315 (round 29): {@code PUT /refills/{id}/reject {}} denied the
 * refill with no reason, and the patient read DENIED with nothing to act on.
 * The refusing handler validates {@code @Validated({Default.class,
 * OnRefusal.class})}, so the body is refused at the edge, by field, before
 * any lookup.</p>
 */
public interface OnRefusal {
}
