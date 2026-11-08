package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA round 30: a minimum interval between doses belongs to an as-needed
 * (PRN) order only; on a fixed schedule ("3 fois par jour", "q6h") the
 * frequency is the interval, and a second one would disagree with it.
 * Reported on {@code prnMinIntervalHours}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PrnMinIntervalOnlyWhenAsNeededValidator.class)
public @interface PrnMinIntervalOnlyWhenAsNeeded {

    String message() default "{prescription.prnMinInterval.notAsNeeded}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
