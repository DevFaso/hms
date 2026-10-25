package com.example.hms.service;

import com.example.hms.payload.dto.PatientHospitalRegistrationRequestDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import com.example.hms.payload.dto.PatientMultiHospitalSummaryDTO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface PatientHospitalRegistrationService {

    PatientHospitalRegistrationResponseDTO registerPatient(PatientHospitalRegistrationRequestDTO dto);

    List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatient(
        UUID patientId, int page, int size, Boolean active);

    /**
     * QA D527: one patient's registrations at ONE hospital, inside the caller's
     * scope — the chart asks "is this patient registered where I act?" and must
     * not depend on that row landing on the first page of every registration.
     */
    List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatientAndHospital(
        UUID patientId, UUID hospitalId, Boolean active);

    PatientHospitalRegistrationResponseDTO updateRegistration(
        UUID id, PatientHospitalRegistrationRequestDTO dto);

    void deregisterPatient(UUID id);

    @Transactional(readOnly = true)
    List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatient(String patientUsername, int page, int size, Boolean active);

    @Transactional
    PatientHospitalRegistrationResponseDTO patchRegistration(String mrn, PatientHospitalRegistrationRequestDTO dto);

    PatientHospitalRegistrationResponseDTO getById(UUID id);

    List<PatientHospitalRegistrationResponseDTO> getRegistrationsByHospital(
        UUID hospitalId, int page, int size, Boolean active);

    @Transactional(readOnly = true)
    List<PatientMultiHospitalSummaryDTO> getPatientsRegisteredInMultipleHospitals();
}
