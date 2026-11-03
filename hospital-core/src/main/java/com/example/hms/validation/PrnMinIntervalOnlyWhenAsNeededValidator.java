package com.example.hms.validation;

import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.service.impl.PrnMinInterval;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** QA round 30: an interval sent with a frequency that is not as-needed, reported on the interval's field. */
public class PrnMinIntervalOnlyWhenAsNeededValidator
        implements ConstraintValidator<PrnMinIntervalOnlyWhenAsNeeded, PrescriptionRequestDTO> {

    @Override
    public boolean isValid(PrescriptionRequestDTO request, ConstraintValidatorContext context) {
        if (request == null || request.getPrnMinIntervalHours() == null
            || PrnMinInterval.asNeeded(request.getFrequency())) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
            .addPropertyNode(PrnMinInterval.FIELD)
            .addConstraintViolation();
        return false;
    }
}
