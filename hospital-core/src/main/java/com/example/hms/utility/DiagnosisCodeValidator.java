package com.example.hms.utility;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class DiagnosisCodeValidator {

    private static final Pattern ICD10_PATTERN = Pattern.compile("^[A-TV-Z]\\d[0-9A-TV-Z](\\.[0-9A-TV-Z]{1,4})?$");

    private DiagnosisCodeValidator() {
    }

    public static boolean isValidIcd10(String value) {
        if (value == null) {
            return false;
        }
        return ICD10_PATTERN.matcher(value).matches();
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    public static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> sanitized = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                sanitized.add(normalized);
            }
        }
        return new ArrayList<>(sanitized);
    }
}
