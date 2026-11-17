package com.example.hms.validation;

import com.example.hms.mapper.StaffMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link KnownJobTitle}: blank, or a {@code JobTitle} name in any case. */
public class KnownJobTitleValidator implements ConstraintValidator<KnownJobTitle, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || StaffMapper.jobTitle(value) != null;
    }
}
