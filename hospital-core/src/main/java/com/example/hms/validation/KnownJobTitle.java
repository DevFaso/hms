package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * #1096 review: a staff job title must be one of the {@code JobTitle} values,
 * whatever its case. An unknown title used to reach {@code JobTitle.valueOf} in
 * the mapper and come back as Java's own English text with no field. Checked at
 * the edge so it is named together with the other refused values (D1371). Null
 * or blank is valid; pair with {@code @NotBlank} when required.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = KnownJobTitleValidator.class)
public @interface KnownJobTitle {

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
