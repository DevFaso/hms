package com.example.hms.service.support;

import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

import java.util.Optional;
import java.util.UUID;

/**
 * The patient row a signed-in account is linked to, for the reads a patient
 * makes about themselves ({@code /me/patient/*}, their documents).
 *
 * <p>{@link PatientRepository#findByUserId} is a single-result query, and
 * {@code V113__patients_user_id_integrity.sql} deliberately falls back to a
 * plain index instead of failing the deploy when a tenant already carries
 * duplicate {@code user_id} rows. On such a tenant the single-result form
 * throws {@link IncorrectResultSizeDataAccessException}, which reached the
 * patient as a 500 on every one of their own reads. The honest answer is the
 * one an unlinked account gets: there is no ONE record this account can be
 * shown, so it is shown none, and the duplicate is logged for an operator to
 * reconcile - ids only.
 *
 * <p>Only for those reads. A path that CREATES a patient row must keep the
 * throwing form: reading "none" there would add a third row to an account
 * that already has two.
 */
@Slf4j
public final class LinkedPatientLookup {

    private LinkedPatientLookup() {
    }

    /** The one patient row linked to {@code userId}; empty when there is none, or more than one. */
    public static Optional<Patient> linkedPatient(PatientRepository patientRepository, UUID userId) {
        try {
            return patientRepository.findByUserId(userId);
        } catch (IncorrectResultSizeDataAccessException ex) {
            log.error("Account {} is linked to {} patient rows (V113 duplicate); its own-record reads "
                    + "answer not-found until the rows are reconciled", userId, ex.getActualSize());
            return Optional.empty();
        }
    }
}
