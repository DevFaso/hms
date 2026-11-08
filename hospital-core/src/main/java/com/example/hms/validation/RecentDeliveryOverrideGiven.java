package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA D1369 (round 31): a labour episode or a delivery for a mother with a
 * birth on record less than 20 weeks away needs an override reason (QA D214).
 * The service refused a missing one only after bean validation passed, so a
 * body that also lacked the onset time named the override reason one request
 * late. Checked here, with every other refusal of the body, and reported on
 * {@code recentDeliveryOverrideReason}. The service keeps its own check (the
 * answer when no request is being read).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RecentDeliveryOverrideGivenValidator.class)
public @interface RecentDeliveryOverrideGiven {

    /** {@code labor.episode.recentDelivery} for an episode, {@code labor.delivery.recentDelivery} for a delivery. */
    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
