package com.example.hms.enums;

/**
 * Visibility tier of a SmartPhrase / dot-phrase macro.
 *
 * <p>Resolution precedence in autocomplete is USER > HOSPITAL > GLOBAL — a
 * user macro with the same trigger as a hospital one shadows the hospital
 * version, and the hospital version shadows the global library. The service
 * layer enforces this; the database just stores the rows.
 */
public enum SmartPhraseScope {
    /** Visible to every user across every tenant — system-shipped library. */
    GLOBAL,
    /** Visible to every clinician at the owning hospital. */
    HOSPITAL,
    /** Private to the owning user. */
    USER
}
