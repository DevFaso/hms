package com.example.hms.validation;

/**
 * Bean-validation group of a field that is required only when the request
 * approves something, while the same body serves the refusal too (the
 * mirror of {@link OnRefusal}).
 *
 * <p>QA D1372 class (round 31): {@code POST /discharge-approvals/{id}/approve
 * {}} named the doctor ids at the edge and the discharge diagnosis one
 * request later, from the service. The approving handler validates
 * {@code @Validated({Default.class, OnApproval.class})}, so every field is
 * named in one refusal, before any lookup.</p>
 */
public interface OnApproval {
}
