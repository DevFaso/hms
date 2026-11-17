package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * #948 review: a calendar day the user picked (a staff start date, a goods
 * receipt date) that may not lie in the future. The portal defaults such a
 * field to the READER's today, while the server's clock is UTC, so east of UTC
 * after local midnight the untouched form carried tomorrow on the server's
 * calendar and {@code @PastOrPresent} refused it. A picked day is therefore
 * accepted up to the day after the server's UTC today, the grace
 * {@code BillingInvoiceServiceImpl} gives an invoice date; see
 * {@link PickedDayNotInFutureValidator#isAllowed}. Null is valid; pair with
 * {@code @NotNull} when required.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PickedDayNotInFutureValidator.class)
public @interface PickedDayNotInFuture {

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
