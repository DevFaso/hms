package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA D1211 (round 23): the one phone rule for every phone field a request can
 * write. "12ab", "xyz", "appeler le voisin" and "pas de tel" were saved as a
 * patient's, a contact's and a guarantor's phone because only the account's
 * main phone was checked (QA D1170). The value must be a phone number: an
 * international number ({@code +} or {@code 00}, E.164) or a national one of
 * 6 to 15 digits, separators allowed ({@code PhoneNumbers.normalizeFacilityPhone}
 * with no country, the most lenient form the facility rule accepts). The
 * service that knows the hospital's country still normalises to E.164 where
 * it can. Null or blank is valid: pair with {@code @NotBlank} when required.
 * Create and update validation only: stored values are not rewritten.
 *
 * <p>{@code PhoneFieldValidationGuardTest} requires it on every phone-named
 * text field of every request payload.</p>
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PhoneNumberValidator.class)
public @interface PhoneNumber {

    /** The message key every phone field answers with. */
    String MESSAGE_KEY = "validation.phone.invalid";

    String message() default "{" + MESSAGE_KEY + "}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
