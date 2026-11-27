package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA D2679 / D1371 class: the colleague named to witness a controlled-drug movement must be someone other than
 * the caller, with a live witness role at the hospital the request acts at. The service refused an ineligible
 * witness only after bean validation passed, so a body that also lacked the witness's password or code named the
 * witness one request late. Checked here, with every other refusal of the body, on the annotated field. The
 * service keeps its own, fuller check (the administrator only as the fallback witness), which decides when no
 * request is being read.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StockWitnessEligibleValidator.class)
public @interface StockWitnessEligible {

    String message() default "{stockTransaction.witness.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
