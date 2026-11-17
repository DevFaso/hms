package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Billing-money plan, "New acceptance criterion" (2026-11-15): a payment or refund free-text field (reference, notes,
 * reason) never holds a payment card number ({@link com.example.hms.utility.CardNumbers}). Null or blank is valid.
 * The refusal message never repeats the value.
 *
 * <p>{@code CardDataFreeTextGuardTest} requires it on every text field of every payment and refund request payload.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoCardNumberValidator.class)
public @interface NoCardNumber {

    String message() default "{" + com.example.hms.utility.CardNumbers.REFUSED_KEY + "}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
