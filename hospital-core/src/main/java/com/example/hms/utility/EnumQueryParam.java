package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;

/**
 * QA D1384 (round 32, the D1350 class): a list filter written as text and
 * read with {@code Enum.valueOf} answered an unknown value with raw Java text
 * ("No enum constant com.example.hms.enums.AdmissionStatus.BOGUS") in every
 * language, with no {@code fieldErrors}. A reader of such a parameter catches
 * the {@code IllegalArgumentException} and throws {@link #refusal}: the
 * value is refused on the parameter's own name, in the reader's language,
 * with the same sentence an unparsable enum parameter gets everywhere else
 * ({@code GlobalExceptionHandler#handleTypeMismatch}). RawEnumValueOfGuardTest
 * keeps an uncaught {@code valueOf(x.toUpperCase())} out of the code. (No
 * generic parser: the house rule keeps {@code <T>} helpers out of
 * hospital-core.)
 */
public final class EnumQueryParam {

    private EnumQueryParam() {
    }

    /** The refusal of an unknown value of the query parameter {@code param}. */
    public static FieldBusinessException refusal(String param) {
        return new FieldBusinessException(param, "error.request.invalidParameter",
            MessageUtil.resolve("field.label." + param));
    }
}
