package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA D1359 (round 30): the administration route of a medication order is
 * one the shared reading knows
 * ({@link com.example.hms.service.emar.MedicationRoutes#canonical}: the
 * form's codes PO, IV, IM, SC, SL, PR, TOP, INH, NEB and their usual English,
 * French and Spanish names). The MAR five-rights route check reads the
 * prescribed route; "BOGUS" or "par la bouche" was stored as typed and could
 * never match a bedside scan. Blank is allowed (no route recorded).
 * {@code MedicationRouteFieldGuardTest} holds every request field named
 * {@code route} to this constraint or to an allowlist entry with why.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MedicationRouteValidator.class)
public @interface MedicationRoute {

    String message() default "{prescription.route.unknown}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
