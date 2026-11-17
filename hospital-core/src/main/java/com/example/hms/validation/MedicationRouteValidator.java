package com.example.hms.validation;

import com.example.hms.service.emar.MedicationRoutes;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** QA D1359: a route {@link MedicationRoutes#canonical} reads, or none. */
public class MedicationRouteValidator implements ConstraintValidator<MedicationRoute, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || MedicationRoutes.canonical(value) != null;
    }
}
