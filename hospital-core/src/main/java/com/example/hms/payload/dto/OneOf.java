package com.example.hms.payload.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(OneOf.List.class)
@Constraint(validatedBy = OneOfValidator.class)
public @interface OneOf {
    String message() default "Provide at least one of the required fields.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /** Field names to check on the annotated class */
    String[] fields();

    @Documented
    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface List {
        OneOf[] value();
    }
}
