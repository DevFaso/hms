package com.example.hms.validation;

import com.example.hms.service.impl.PrnMinInterval;
import jakarta.validation.ConstraintValidatorContext;

/** QA D1378: one as-needed refusal reported on its own field, in the request's language. */
final class PrnRefusalReport {

    private PrnRefusalReport() {
    }

    /** @return {@code true} when there is nothing to report */
    static boolean report(ConstraintValidatorContext context, PrnMinInterval.Refusal refusal) {
        if (refusal == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("{" + refusal.key() + "}")
            .addPropertyNode(refusal.field())
            .addConstraintViolation();
        return false;
    }
}
