package com.example.hms.controller.support;

import com.example.hms.exception.InvalidQueryParameterException;

import java.util.Collection;
import java.util.Locale;

/**
 * The one way a {@code String} query parameter that filters on a closed list
 * is read (QA D1385, round 32): trimmed, any case, and refused by name when it
 * is not one of the codes - never matched against nothing. Before it,
 * {@code /reception/waitlist?status=BOGUS} and {@code /reception/queue?status=waiting}
 * answered 200 with an empty list. {@code QueryParameterVocabularyGuardTest}
 * keeps every such parameter on this path, or listed with where it is checked.
 */
public final class QueryParameterValues {

    private QueryParameterValues() {
    }

    /**
     * The code {@code raw} names, upper-cased, or null when it is absent or
     * blank (no filter).
     *
     * @throws InvalidQueryParameterException naming {@code parameter} when the
     *     value is none of {@code codes} (upper-case codes)
     */
    public static String oneOf(String parameter, String raw, Collection<String> codes) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (!codes.contains(code)) {
            throw new InvalidQueryParameterException(parameter);
        }
        return code;
    }
}
