package com.example.hms.validation;

/**
 * Bean-validation group of a constraint that holds only when a record is
 * CREATED (the invoice's patient, the invoice line's invoice): the update
 * path leaves those fields unchanged when absent.
 *
 * <p>QA D1300 (round 28): those fields were checked in the service, after
 * bean validation had already refused the body for its other fields, so an
 * empty body named them one press later. A create handler validates
 * {@code @Validated({Default.class, OnCreate.class})}, so one refusal names
 * them all.</p>
 */
public interface OnCreate {
}
