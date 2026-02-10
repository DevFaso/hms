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
                f.setAccessible(true);
                Object v = f.get(value);
                if (v == null) continue;

                if (v instanceof CharSequence s) {
                    if (s.toString().trim().isEmpty()) continue;
                }
                // Non-null (and non-blank for strings) counts as present
                present++;
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}
        }

        return present >= 1;
    }
}
