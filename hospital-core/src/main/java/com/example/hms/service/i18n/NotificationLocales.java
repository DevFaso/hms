package com.example.hms.service.i18n;

import java.util.Locale;

/**
 * Which language a stored notification is written in when the reader is not
 * the caller.
 *
 * <p>A notification body is persisted once and read later by its recipient, so
 * rendering it in the caller's request locale would bake the sender's language
 * into something somebody else reads. Staff have no per-user language
 * preference anywhere in the schema (only patients state one, on the
 * medical-history tab — see {@link PatientLocaleResolver}), and the product is
 * French-first, so staff-facing bodies are rendered in French. Patient-facing
 * bodies go through {@link PatientLocaleResolver#resolve} with
 * {@link #PATIENT_FALLBACK} for patients who stated no deliverable language.
 *
 * <p>The day a staff language preference exists, {@link #STAFF} is the one
 * place to replace with a lookup.
 */
public final class NotificationLocales {

    /** Locale for bodies read by clinicians and administrators. */
    public static final Locale STAFF = Locale.FRENCH;

    /** Locale for a patient who stated no language the bundles can render. */
    public static final Locale PATIENT_FALLBACK = Locale.FRENCH;

    /**
     * Locale for SMS read by a partner pharmacy. Pharmacies have no stated
     * language anywhere in the schema and the reply codes (1 / 2 / 3) are
     * parsed language-independently, so the wording follows the product's
     * French-first default. The one place to replace with a lookup when a
     * pharmacy-level preference exists.
     */
    public static final Locale PARTNER_PHARMACY = Locale.FRENCH;

    private NotificationLocales() {
    }
}
