package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Review of #1285 (QA D1371 rule): a preparation cancelled as « stock manquant ou endommagé » never goes back on sale
 * as intact. Refused at the edge on {@code stockCondition}, with every other refusal of the request; the service
 * keeps the check as a defence.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StockUnavailableNotIntactValidator.class)
public @interface StockUnavailableNotIntact {

    String message() default "{dispense.ready.stockUnavailableNotIntact}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
