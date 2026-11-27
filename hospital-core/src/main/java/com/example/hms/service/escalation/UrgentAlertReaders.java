package com.example.hms.service.escalation;

import com.example.hms.model.User;

/** QA D4102, D4104 (r81): how a member of staff is named on an acknowledged alert and on the incident register. */
public final class UrgentAlertReaders {

    private UrgentAlertReaders() {
    }

    /** First and last name; the username for an account with neither. */
    public static String displayName(User user) {
        if (user == null) {
            return null;
        }
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
