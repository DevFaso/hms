package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint for the
 * {@link com.example.hms.model.PatientConsent#getScope()} CSV. Each comma-
 * separated token must match a {@link com.example.hms.enums.DataDomain} value
 * (case-insensitive); whitespace is ignored. Null and blank values are valid
 * (interpreted as "all non-sensitive domains" by the resolution layer).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DataDomainCsvValidator.class)
public @interface DataDomainCsv {

    String message() default "scope must be a comma-separated list of valid DataDomain values";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
