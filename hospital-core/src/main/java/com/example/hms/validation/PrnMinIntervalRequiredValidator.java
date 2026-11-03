package com.example.hms.validation;

import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.service.impl.PrnMinInterval;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** QA round 30: an as-needed frequency without its minimum interval, reported on the interval's field. */
public class PrnMinIntervalRequiredValidator
        implements ConstraintValidator<PrnMinIntervalRequired, PrescriptionRequestDTO> {

    @Override
    public boolean isValid(PrescriptionRequestDTO request, ConstraintValidatorContext context) {
        if (request == null || request.getPrnMinIntervalHours() != null
            || !PrnMinInterval.asNeeded(request.getFrequency())) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
            .addPropertyNode(PrnMinInterval.FIELD)
            .addConstraintViolation();
        return false;
    }
}
