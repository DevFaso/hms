package com.example.hms.payload.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.lang.reflect.Field;

public class OneOfValidator implements ConstraintValidator<OneOf, Object> {

    private String[] fields;

    @Override
    public void initialize(OneOf constraintAnnotation) {
        this.fields = constraintAnnotation.fields();
    }

    @Override
    @SuppressWarnings("java:S3011")
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int present = 0;

        for (String fieldName : fields) {
            present += countFieldPresence(value, fieldName);
        }
        if (present >= 1) {
            return true;
        }
        // QA D1245 (round 25): a class-level refusal reached the client with an
        // EMPTY fieldErrors ("Nouvelle ordonnance" with no patient). It is
        // reported on the first field, the one a form asks for.
        if (context != null && fields.length > 0) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(fields[0])
                .addConstraintViolation();
        }
        return false;
    }

    @SuppressWarnings("java:S3011")
    private int countFieldPresence(Object value, String fieldName) {
        try {
            Field f = value.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(value);
            if (v == null) {
                return 0;
            }
            if (v instanceof CharSequence s && s.toString().trim().isEmpty()) {
                return 0;
            }
            return 1;
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return 0;
        }
    }
}
