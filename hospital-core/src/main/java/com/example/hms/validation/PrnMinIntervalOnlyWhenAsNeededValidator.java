package com.example.hms.validation;

import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.service.impl.PrnMinInterval;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * QA round 30 / QA D1378 (round 32): a request that says {@code asNeeded:
 * false} carries no interval. On create and on update (an update that leaves
 * the tick out keeps the stored one, and the service rule
 * {@code PrnMinInterval.applyTo} decides it). Only the interval is checked
 * here: an update may be a withdrawal echoing an older order's frequency,
 * which the safety net must never refuse (the create constraint and the
 * service rule apply it to written content).
 */
public class PrnMinIntervalOnlyWhenAsNeededValidator
        implements ConstraintValidator<PrnMinIntervalOnlyWhenAsNeeded, PrescriptionRequestDTO> {

    @Override
    public boolean isValid(PrescriptionRequestDTO request, ConstraintValidatorContext context) {
        if (request == null || !Boolean.FALSE.equals(request.getAsNeeded())
            || request.getPrnMinIntervalHours() == null) {
            return true;
        }
        return PrnRefusalReport.report(context,
            new PrnMinInterval.Refusal(PrnMinInterval.FIELD, PrnMinInterval.NOT_AS_NEEDED_KEY));
    }
}
