package com.example.hms.validation;

import com.example.hms.service.support.ClinicalContent;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/** Validates {@link AnyAnswer} with the same reading as {@code ClinicalContent.requireAnyAnswer}. */
public class AnyAnswerValidator implements ConstraintValidator<AnyAnswer, Object> {

    private Set<String> metadata = Set.of();
    private String reportedOn;

    @Override
    public void initialize(AnyAnswer annotation) {
        metadata = Set.of(annotation.metadata());
        reportedOn = annotation.reportedOn();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null || ClinicalContent.hasAnyAnswer(value, metadata)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
            .addPropertyNode(reportedOn)
            .addConstraintViolation();
        return false;
    }
}
