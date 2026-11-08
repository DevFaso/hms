package com.example.hms.service.support;

import com.example.hms.exception.FieldBusinessException;
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

    /** QA D1476: see {@link #refuseDeactivatedRegistration(PatientHospitalRegistrationRepository, UUID, UUID)}. */
    public void refuseDeactivatedRegistration(UUID patientId, UUID hospitalId) {
        refuseDeactivatedRegistration(registrationRepository, patientId, hospitalId);
    }

    /**
     * QA D1476: a visit is not opened on a registration the desk deactivated (booking,
     * walk-in, waitlist): the pickers hide the patient, the API did not refuse. Refused
     * on the patient, naming why; the record itself and its history stay readable, and a
     * visit already booked can still be changed or cancelled.
     */
    public static void refuseDeactivatedRegistration(PatientHospitalRegistrationRepository registrations,
                                                     UUID patientId, UUID hospitalId) {
        if (patientId != null && hospitalId != null
            && registrations.existsByPatientIdAndHospitalIdAndActiveFalse(patientId, hospitalId)) {
            throw new FieldBusinessException("patientId", "patient.registration.inactive");
        }
    }
}
