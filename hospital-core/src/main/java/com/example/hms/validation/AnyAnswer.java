package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A record of many optional answers says something: at least one property
 * other than the {@link #metadata()} ones (ids, dates of record, version
 * flags) carries a value, the rule of
 * {@code ClinicalContent.requireAnyAnswer}.
 *
 * <p>QA D1318 class (round 29): the rule ran in the service, after bean
 * validation had refused the body for its ids, so an empty social history or
 * high-risk plan named the patient first and its content one request later.
 * Declared on the body, it is checked with the other fields and reported on
 * {@link #reportedOn()} in the same answer.</p>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(AnyAnswer.List.class)
@Constraint(validatedBy = AnyAnswerValidator.class)
public @interface AnyAnswer {

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Properties that are not answers. */
    String[] metadata();

    /** The request field the refusal marks. */
    String reportedOn();

    @Documented
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        AnyAnswer[] value();
    }
}
