package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Billing plan, owner decision D-15 (2026-11-15): e-Keneya never collects
 * card numbers, CVVs or PINs. A card or Mobile Money payment completed outside
 * e-Keneya is recorded with its amount, method, operator and transaction
 * reference only.
 *
 * <p>A payment or refund free-text field (reference, notes, reason) refuses a
 * value that contains something that looks like a card number: 13 to 19
 * digits, spaces or dashes allowed between them, that pass the Luhn check
 * ({@link NoCardNumberValidator#containsCardNumber}). The refusal names the
 * field and never repeats the value, and nothing logs it. Null or blank is
 * valid.
 *
 * <p>{@code PaymentFreeTextCardNumberGuardTest} requires it on every free-text
 * field of every payment and refund request payload.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoCardNumberValidator.class)
public @interface NoCardNumber {

    /** The message key every such field answers with. */
    String MESSAGE_KEY = "validation.payment.cardNumberRefused";

    String message() default "{" + MESSAGE_KEY + "}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
