package com.example.hms.imaging.dicom;

/**
 * DICOM UID syntax (PS3.5 §9.1): dot-separated numeric components, at most
 * 64 characters. Every UID that reaches a DICOMweb URL is checked here first,
 * so no caller can steer the upstream path ({@code ../}, {@code ?}, {@code #}).
 */
public final class DicomUid {

    public static final int MAX_LENGTH = 64;

    /**
     * The pattern a request DTO repeats in its {@code @Pattern}. Possessive
     * quantifiers: no backtracking, so no stack growth on a long input. The
     * DTO's {@code @Size(max = 64)} bounds it too.
     */
    public static final String REGEX = "^\\d++(?:\\.\\d++)*+$";

    private DicomUid() {
    }

    /** Digits in groups separated by single dots, no leading/trailing dot, at most 64 characters. */
    public static boolean isValid(String uid) {
        if (uid == null || uid.isEmpty() || uid.length() > MAX_LENGTH) {
            return false;
        }
        boolean previousWasDigit = false;
        for (int i = 0; i < uid.length(); i++) {
            char c = uid.charAt(i);
            if (c >= '0' && c <= '9') {
                previousWasDigit = true;
            } else if (c == '.' && previousWasDigit) {
                previousWasDigit = false;
            } else {
                return false;
            }
        }
        return previousWasDigit;
    }
}
