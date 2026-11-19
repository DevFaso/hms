package com.example.hms.imaging.dicom;

import java.util.regex.Pattern;

/**
 * DICOM UID syntax (PS3.5 §9.1): dot-separated numeric components, at most
 * 64 characters. Every UID that reaches a DICOMweb URL is checked here first,
 * so no caller can steer the upstream path ({@code ../}, {@code ?}, {@code #}).
 */
public final class DicomUid {

    public static final int MAX_LENGTH = 64;

    /** The pattern a request DTO repeats in its {@code @Pattern}. */
    public static final String REGEX = "^[0-9]+(\\.[0-9]+)*$";

    private static final Pattern UID = Pattern.compile(REGEX);

    private DicomUid() {
    }

    public static boolean isValid(String uid) {
        return uid != null && !uid.isEmpty() && uid.length() <= MAX_LENGTH && UID.matcher(uid).matches();
    }
}
