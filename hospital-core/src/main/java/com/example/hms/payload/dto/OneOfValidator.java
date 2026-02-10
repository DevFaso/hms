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
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) return true;
        int present = 0;

        for (String fieldName : fields) {
            try {
                Field f = value.getClass().getDeclaredField(fieldName);
                Object v = f.get(value);
                if (v != null) {
                    boolean isBlankCharSequence = v instanceof CharSequence s && s.toString().trim().isEmpty();
                    if (!isBlankCharSequence) {
                        // Non-null (and non-blank for strings) counts as present
                        present++;
                    }
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) { /* Field not found or inaccessible — skip gracefully */ }
        }

        return present >= 1;
    }
}
