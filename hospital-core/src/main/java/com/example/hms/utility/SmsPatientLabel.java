package com.example.hms.utility;

import com.example.hms.model.Patient;

import java.util.Locale;

/**
 * Scorecard K4 (minimum disclosure): how a patient is named in a text message. An SMS is read on a
 * shared phone, kept in a lock-screen preview and forwarded, so it never carries the full name: the
 * initials, or the first name with the family name's initial where the reader must tell two
 * registrations apart. The full name, the test, the value and the diagnosis stay behind the sign-in.
 */
public final class SmsPatientLabel {

    /** What an SMS says when the patient's name is not recorded. */
    public static final String UNKNOWN = "—";

    private SmsPatientLabel() {
    }

    /**
     * The patient's initials, every recorded name in order ({@code "AMO"} for Awa Mariam Ouédraogo, {@code "AO"}
     * without a middle name); {@link #UNKNOWN} for no patient or no name.
     */
    public static String initials(Patient patient) {
        return patient == null ? UNKNOWN
            : initials(patient.getFirstName(), patient.getMiddleName(), patient.getLastName());
    }

    /** The initials of {@code names}, in order, skipping the blank ones; {@link #UNKNOWN} when all are blank. */
    public static String initials(String... names) {
        StringBuilder sb = new StringBuilder(names.length);
        for (String name : names) {
            char c = initial(name);
            if (c != 0) {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? UNKNOWN : sb.toString();
    }

    /** {@code "Awa O."} for Awa Ouédraogo; the initials when there is no first name. */
    public static String firstNameAndInitial(String firstName, String lastName) {
        if (firstName == null || firstName.isBlank()) {
            return initials(null, lastName);
        }
        char last = initial(lastName);
        return last == 0 ? firstName.trim() : firstName.trim() + " " + last + ".";
    }

    private static char initial(String s) {
        return (s == null || s.isBlank()) ? 0 : s.trim().substring(0, 1).toUpperCase(Locale.ROOT).charAt(0);
    }
}
