package com.example.hms.validation;

import com.example.hms.service.lab.CriticalResultEscalationLadder;
import com.example.hms.utility.MessageUtil;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA r70 (C5): the three steps of a hospital's critical-result ladder increase, from
 * {@value CriticalResultEscalationLadder#MIN_MINUTES} minutes to a day. Checked on the request body, with the
 * required-field checks, so one refusal names every field (QA D1371): the steps are judged together, and all
 * three are named when the set is wrong. A step left out is {@code @NotNull}'s to name; nothing is added here.
 */
@Documented
@Constraint(validatedBy = IncreasingEscalationSteps.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface IncreasingEscalationSteps {

    String message() default "{lab.criticalEscalation.minutesInvalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** What the constraint reads: the three steps, in minutes. */
    interface Steps {
        Integer coverAfterMinutes();

        Integer leadAfterMinutes();

        Integer unresolvedAfterMinutes();
    }

    /** Names the three fields together when the set does not increase within the range. */
    class Validator implements ConstraintValidator<IncreasingEscalationSteps, Steps> {

        @Override
        public boolean isValid(Steps steps, ConstraintValidatorContext context) {
            if (steps == null || steps.coverAfterMinutes() == null || steps.leadAfterMinutes() == null
                    || steps.unresolvedAfterMinutes() == null) {
                return true;
            }
            if (new CriticalResultEscalationLadder.Minutes(steps.coverAfterMinutes(), steps.leadAfterMinutes(),
                    steps.unresolvedAfterMinutes()).valid()) {
                return true;
            }
            // Rendered here: the text takes the two bounds, which a constraint template cannot pass.
            String text = MessageUtil.resolveOrRaw("lab.criticalEscalation.minutesInvalid",
                CriticalResultEscalationLadder.MIN_MINUTES, CriticalResultEscalationLadder.MAX_MINUTES)
                .replace("{", "(").replace("}", ")").replace("$", "");
            context.disableDefaultConstraintViolation();
            for (String field : new String[] {"coverAfterMinutes", "leadAfterMinutes", "unresolvedAfterMinutes"}) {
                context.buildConstraintViolationWithTemplate(text).addPropertyNode(field).addConstraintViolation();
            }
            return false;
        }
    }
}
