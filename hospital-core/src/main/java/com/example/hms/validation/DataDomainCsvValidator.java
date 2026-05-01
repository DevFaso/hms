package com.example.hms.validation;

import com.example.hms.enums.DataDomain;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DataDomainCsvValidator implements ConstraintValidator<DataDomainCsv, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            DataDomain.parseCsv(value);
            return true;
        } catch (IllegalArgumentException ex) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                "Invalid DataDomain in scope: " + ex.getMessage()
            ).addConstraintViolation();
            return false;
        }
    }
}
