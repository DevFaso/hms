package com.example.hms.service.clinicalrules;

/** Provisional clinical-override documentation rule, pending clinical approval. */
@ProvisionalRule(ClinicalRuleSetKey.CLINICAL_OVERRIDE_REASON_MINIMUM)
public final class ClinicalOverrideReason {
    public static final int MIN_NON_WHITESPACE = 10;

    private ClinicalOverrideReason() { }

    private static boolean whitespace(int ch) {
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch) || ch == 0xfeff;
    }

    public static String trim(String reason) {
        if (reason == null) return null;
        int start = 0;
        int end = reason.length();
        while (start < end && whitespace(reason.codePointAt(start))) {
            start += Character.charCount(reason.codePointAt(start));
        }
        while (end > start && whitespace(reason.codePointBefore(end))) {
            end -= Character.charCount(reason.codePointBefore(end));
        }
        return reason.substring(start, end);
    }

    public static boolean isSufficient(String reason) {
        if (reason == null) return false;
        return trim(reason).codePoints().filter(ch -> !whitespace(ch)).count() >= MIN_NON_WHITESPACE;
    }
}
