package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/**
 * QA D1403 + D1414: the one rule for a patient's birth date and names, on every
 * path that creates or edits them. A birth date must be in the past (the
 * entity's {@code @Past}, refused here in the reader's language instead of at
 * flush in raw English) and at most {@value #OLDEST_AGE_YEARS} years back; a name
 * cannot be made of digits only ("A1" is a name, "12345" is not).
 *
 * <p>Only a CHANGE is judged. A record stored before these rules (a 1800 birth
 * date, a digit name) stays editable on its other fields, including through a
 * client that resends the whole profile. This is why the rule is here and not a
 * constraint on the entity: a constraint would fail every later save of that
 * record, whatever was edited.
 */
public final class PatientIdentityRules {

    public static final int OLDEST_AGE_YEARS = 130;

    private static final String FIELD_DATE_OF_BIRTH = "dateOfBirth";

    private PatientIdentityRules() {
    }

    /** Refuses a new birth date on its field; {@code stored} (null on a create) is never judged. */
    public static void requirePossibleBirthDate(LocalDate value, LocalDate stored) {
        if (value == null || value.equals(stored)) {
            return;
        }
        String problem = birthDateProblem(value, today());
        if (problem != null) {
            throw new FieldBusinessException(FIELD_DATE_OF_BIRTH, problem);
        }
    }

    /** Refuses a new name made of digits on its field; {@code stored} (null on a create) is never judged. */
    public static void requireNameWithLetters(String field, String value, String stored) {
        if (value == null || Objects.equals(value.trim(), stored == null ? null : stored.trim())) {
            return;
        }
        if (isMadeOfDigits(value)) {
            throw new FieldBusinessException(field, "patient.name.digitsOnly");
        }
    }

    /** The message key of what is wrong with this birth date, or null when it is possible. */
    public static String birthDateProblem(LocalDate value, LocalDate today) {
        if (!value.isBefore(today)) {
            return "patient.dateOfBirth.notPast";
        }
        if (value.isBefore(today.minusYears(OLDEST_AGE_YEARS))) {
            return "patient.dateOfBirth.impossible";
        }
        return null;
    }

    /** At least one digit and no letter, whatever separates them. A dash alone (the "-" placeholder) is not. */
    public static boolean isMadeOfDigits(String value) {
        boolean digit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetter(c)) {
                return false;
            }
            digit |= Character.isDigit(c);
        }
        return digit;
    }

    /** The calendar the entity's {@code @Past} reads, so what passes here passes at flush. */
    public static LocalDate today() {
        return LocalDate.now(Clock.systemDefaultZone());
    }
}
