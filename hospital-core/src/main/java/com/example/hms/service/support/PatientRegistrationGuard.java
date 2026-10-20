package com.example.hms.service.support;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one check every clinical WRITE that names a patient must pass: the
 * patient is registered at the hospital the record is written in.
 *
 * <p>QA D401: hospital B's receptionist booked an appointment, and B's doctor
 * wrote a prescription (with an auto-created encounter), for a patient
 * registered only at hospital A. {@code POST /encounters}, vitals and invoices
 * already refused that with a 404; appointments and prescriptions did not.
 * The answer is the same 404 an unknown patient gets, so a caller cannot use
 * the write to learn that the id is real somewhere else.
 *
 * <p>{@code PatientWriteRegistrationCoverageTest} fails when a new write
 * service takes a patient id without going through this guard (or the
 * repository check it wraps).
 */
@Component
@RequiredArgsConstructor
public class PatientRegistrationGuard {

    private static final String PATIENT_NOT_FOUND = "patient.notfound";

    private final PatientHospitalRegistrationRepository registrationRepository;

    /**
     * Throws the unknown-patient 404 unless the patient holds a registration
     * at {@code hospitalId}. A null hospital is refused too: a write with no
     * hospital to check against is not proof the patient belongs there.
     */
    public void requireRegisteredAt(UUID patientId, UUID hospitalId) {
        if (patientId == null || hospitalId == null
            || !registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)) {
            throw new ResourceNotFoundException(PATIENT_NOT_FOUND, patientId);
        }
    }
}
