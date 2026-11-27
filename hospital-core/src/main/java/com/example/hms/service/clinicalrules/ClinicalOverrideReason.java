package com.example.hms.service.clinicalrules;

/** Provisional clinical-override documentation rule, pending clinical approval. */
public final class ClinicalOverrideReason {
    public static final int MIN_NON_WHITESPACE = 10;

    private ClinicalOverrideReason() { }

    public static boolean isSufficient(String reason) {
        if (reason == null) return false;
        return reason.strip().codePoints()
            .filter(ch -> !Character.isWhitespace(ch) && !Character.isSpaceChar(ch))
            .limit(MIN_NON_WHITESPACE).count() >= MIN_NON_WHITESPACE;
    }
}
