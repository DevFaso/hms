package com.example.hms.validation;

import com.example.hms.utility.PhoneNumbers;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Validator behind {@link PhoneNumber}. */
public class PhoneNumberValidator implements ConstraintValidator<PhoneNumber, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return isPhoneNumber(value);
    }

    /** The rule itself, for a service that checks a value outside bean validation. */
    public static boolean isPhoneNumber(String value) {
        return value == null || value.isBlank() || PhoneNumbers.normalizeFacilityPhone(value, null) != null;
    }
}
