package com.example.hms.validation;

import com.example.hms.enums.DataDomain;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public class DataDomainCsvValidator implements ConstraintValidator<DataDomainCsv, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        // Iterate token-by-token so the user-facing error names the bad token
        // explicitly (Enum.valueOf's IllegalArgumentException leaks the
        // internal class name and gives no list of allowed values).
        for (String raw : value.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                DataDomain.valueOf(token.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                    "Invalid scope value: '" + token + "'. Allowed values: " + allowedValuesList()
                ).addConstraintViolation();
                return false;
            }
        }
        return true;
    }

    private static String allowedValuesList() {
        return Arrays.stream(DataDomain.values())
            .map(Enum::name)
            .collect(Collectors.joining(", "));
    }
}
