package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Review of #1285 (QA D1300 / D1349 rule): a RETURN stock movement says the state of its units
 * ({@code stockCondition}), named at the edge with every other missing field instead of one press later by the
 * service. Required only for a RETURN; the service keeps the check as a defence.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ReturnStockStateRequiredValidator.class)
public @interface ReturnStockStateRequired {

    String message() default "{stockTransaction.return.stockConditionRequired}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
