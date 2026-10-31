package com.example.hms.service.emar;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.pharmacy.PrescriptionControlledResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * QA D922 (user decision 2026-10-28): the one copy of the witness rule at
 * administration. A dose of a controlled drug is GIVEN only with a second
 * clinician of the same hospital as witness, never the person giving it, as
 * real hospitals require. Both the station MAR and the eMAR record the dose
 * through {@code NurseTaskServiceImpl#recordMedicationAdministration}, which
 * calls this before anything is written.
 *
 * <p>"Controlled" is {@link PrescriptionControlledResolver}'s answer: the
 * prescriber's tick OR the hospital catalogue's flag. A drug the catalogue
 * marks controlled is always controlled, whatever the prescriber ticked; this
 * supersedes decision D654 ("the guard keys only on the prescriber's tick")
 * for administration.
 */
@Component
@RequiredArgsConstructor
public class ControlledAdministrationWitness {

    /** Who may witness a dose: a second nurse, midwife or doctor of the hospital. */
    public static final Set<String> WITNESS_ROLE_CODES = Set.of("ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_DOCTOR");

    private final PrescriptionControlledResolver controlledResolver;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;

    /**
     * The witness of this administration: {@code null} when none is needed
     * (not a GIVEN dose, or not a controlled drug: any witness sent is then not
     * recorded, so the column means one thing), otherwise the validated
     * witness. Refuses, in the caller's language, a missing witness, the
     * administering user, and anyone who is not an active clinician of the
     * prescription's hospital.
     */
    public User resolve(Prescription prescription, MedicationAdministrationStatus status,
                        UUID witnessUserId, UUID administeringUserId) {
        if (status != MedicationAdministrationStatus.GIVEN || prescription == null
                || !controlledResolver.isControlled(prescription)) {
            return null;
        }
        if (witnessUserId == null) {
            throw new BusinessException("nurse.mar.witness.required",
                prescription.getMedicationName() != null ? prescription.getMedicationName() : "-");
        }
        if (witnessUserId.equals(administeringUserId)) {
            throw new BusinessException("nurse.mar.witness.notSelf");
        }
        UUID hospitalId = prescription.getHospital() != null ? prescription.getHospital().getId() : null;
        if (hospitalId == null
                || !assignmentRepository.existsActiveWitness(witnessUserId, hospitalId, WITNESS_ROLE_CODES)) {
            throw new BusinessException("nurse.mar.witness.invalid");
        }
        return userRepository.findById(witnessUserId)
            .orElseThrow(() -> new BusinessException("nurse.mar.witness.invalid"));
    }
}
