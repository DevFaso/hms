package com.example.hms.validation;

import com.example.hms.payload.dto.LabTestReferenceRangeDTO;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

/** QA D896: one violation per inconsistent critical limit, on that limit's field. */
public class CriticalLimitsConsistentValidator
        implements ConstraintValidator<CriticalLimitsConsistent, LabTestReferenceRangeDTO> {

    @Override
    public boolean isValid(LabTestReferenceRangeDTO range, ConstraintValidatorContext context) {
        if (range == null) {
            return true;
        }
        List<LabTestReferenceRangeDTO.CriticalLimitProblem> problems = range.criticalLimitProblems();
        if (problems.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        for (LabTestReferenceRangeDTO.CriticalLimitProblem problem : problems) {
            context.buildConstraintViolationWithTemplate("{" + problem.messageKey() + "}")
                .addPropertyNode(problem.field())
                .addConstraintViolation();
        }
        return false;
    }
}
