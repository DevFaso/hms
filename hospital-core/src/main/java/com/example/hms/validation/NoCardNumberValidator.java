package com.example.hms.validation;

import com.example.hms.utility.CardNumbers;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Validator behind {@link NoCardNumber}. */
public class NoCardNumberValidator implements ConstraintValidator<NoCardNumber, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return !CardNumbers.containsCardNumber(value);
    }
}
