package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA D896: the critical (panic) limits of a reference range must be ordered
 * and lie outside the range. The check used to be one {@code @AssertTrue} on
 * a synthetic property, so a refusal named no field, and on the PUT of a
 * range list it reached the caller as Spring's English "Validation failure".
 * This class-level constraint reports each inconsistent limit on its own
 * field ({@code criticalLow} or {@code criticalHigh}) with a message that
 * names it. The rule itself is
 * {@link com.example.hms.payload.dto.LabTestReferenceRangeDTO#criticalLimitProblems()}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CriticalLimitsConsistentValidator.class)
public @interface CriticalLimitsConsistent {

    String message() default "{lab.referenceRange.criticalInverted}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
