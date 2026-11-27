package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = ClinicalOverrideReasonValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface ClinicalOverrideReasonRequired {
    String message() default "{clinical.override.reasonMin}";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
