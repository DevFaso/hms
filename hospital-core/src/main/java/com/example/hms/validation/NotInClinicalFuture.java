package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA D714/D745: the one rule for a clinical timestamp that records something
 * that already happened (an admission, a birth, an assessment, an observation,
 * a history entry). It may not lie in the future, beyond a small allowance for
 * a charting device whose clock runs slightly ahead of the server
 * ({@link NotInClinicalFutureValidator#CLOCK_SKEW}). A date-only value may not
 * be after today. Null is valid; pair with {@code @NotNull} when required.
 *
 * <p>{@code ClinicalTimestampFutureGuardTest} requires this (or
 * {@code @PastOrPresent}/{@code @Past}) on every date/time field of the
 * maternity and newborn request DTOs, unless the field is a planned date.</p>
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotInClinicalFutureValidator.class)
public @interface NotInClinicalFuture {

    String message() default "{clinical.timestamp.future}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
