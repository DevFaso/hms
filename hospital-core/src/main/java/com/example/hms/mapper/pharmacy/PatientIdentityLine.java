package com.example.hms.mapper.pharmacy;

import com.example.hms.model.Patient;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.UUID;

/**
 * QA D4185 (r81): what a pharmacy row says of its patient beyond the name. Two patients with the same name could
 * not be told apart at the counter: the dispensing queue, the dispense panel, the checkout's fill picker and the
 * claims list showed a name only. Every pharmacy DTO that names a patient carries the record number at the acting
 * hospital (never another hospital's) and the date of birth from here ({@code PharmacyPatientIdentityGuardTest}).
 */
public final class PatientIdentityLine {

    private PatientIdentityLine() {
    }

    /** The patient's record number at {@code hospitalId}, or null; a detached proxy answers null. */
    public static String mrnOf(Patient patient, UUID hospitalId) {
        if (patient == null || hospitalId == null) {
            return null;
        }
        try {
            String mrn = patient.getMrnForHospital(hospitalId);
            return StringUtils.hasText(mrn) ? mrn : null;
        } catch (org.hibernate.LazyInitializationException detached) {
            return null;
        }
    }

    /** The patient's date of birth, or null; a detached proxy answers null. */
    public static LocalDate dateOfBirthOf(Patient patient) {
        if (patient == null) {
            return null;
        }
        try {
            return patient.getDateOfBirth();
        } catch (org.hibernate.LazyInitializationException detached) {
            return null;
        }
    }
}
