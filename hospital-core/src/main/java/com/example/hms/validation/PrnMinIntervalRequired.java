package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA round 30 (user decision 2026-11-02): a new as-needed (PRN) prescription
 * names its minimum interval between doses. Reported on
 * {@code prnMinIntervalHours} with every other refusal of the request (the
 * D1349 rule). Used in the {@link OnCreate} group only: a withdrawal of an
 * older as-needed order, written before the field existed, is never refused
 * for it (the service rule {@code PrnMinInterval.applyTo} decides an edit of
 * the order's content).
 *
 * <p>QA D1378 (round 32): as-needed is the prescriber's explicit tick
 * ({@code asNeeded}); the frequency text only feeds the safety net, refused on
 * {@code asNeeded} when it looks conditional on an order not ticked.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PrnMinIntervalRequiredValidator.class)
public @interface PrnMinIntervalRequired {

    String message() default "{prescription.prnMinInterval.required}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
