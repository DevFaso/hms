package com.example.hms.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import com.example.hms.service.clinicalrules.ClinicalOverrideReason;

public class ClinicalOverrideReasonValidator implements ConstraintValidator<ClinicalOverrideReasonRequired, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return ClinicalOverrideReason.isSufficient(value);
    }
}
