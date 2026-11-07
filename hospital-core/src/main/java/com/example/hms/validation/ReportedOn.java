package com.example.hms.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The request field a cross-field rule ({@code @AssertTrue} on a getter) is
 * reported on. Without it the refusal's {@code fieldErrors} key is the
 * getter's own name ({@code referenceGivenForNonCashPayment}), which no form
 * control carries, so the form marks nothing (QA D1317, round 29).
 * {@code GlobalExceptionHandler} keys the refusal on {@link #value()}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReportedOn {

    /** The JSON name of the request field the form marks. */
    String value();
}
