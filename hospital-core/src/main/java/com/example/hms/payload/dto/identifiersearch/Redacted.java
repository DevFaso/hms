package com.example.hms.payload.dto.identifiersearch;

/** How a search body prints itself: which fields were given, never their values. */
final class Redacted {

    private Redacted() {
    }

    static String presence(Object value) {
        return value == null ? "absent" : "given";
    }
}
