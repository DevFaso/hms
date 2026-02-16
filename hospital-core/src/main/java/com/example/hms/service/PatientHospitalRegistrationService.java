package com.example.hms.service;

import com.example.hms.payload.dto.PatientHospitalRegistrationRequestDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import com.example.hms.payload.dto.PatientMultiHospitalSummaryDTO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@SuppressWarnings("java:S1133") // Deprecated methods retained for backward compatibility
public interface PatientHospitalRegistrationService {

    PatientHospitalRegistrationResponseDTO registerPatient(PatientHospitalRegistrationRequestDTO dto);

    List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatient(
        UUID patientId, int page, int size, Boolean active);

    /**
     * @deprecated use {@link #getRegistrationsByPatient(UUID, int, int, Boolean)} to control pagination and filters.
     */
    @Deprecated(since = "2025", forRemoval = true)
    @Transactional(readOnly = true)
    List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatient(UUID patientId);

    PatientHospitalRegistrationResponseDTO updateRegistration(
        UUID id, PatientHospitalRegistrationRequestDTO dto);

    /**
     * @deprecated prefer {@link #updateRegistration(UUID, PatientHospitalRegistrationRequestDTO)} which supports IDs directly.
     */
    @Deprecated(since = "2025", forRemoval = true)
    @Transactional
    PatientHospitalRegistrationResponseDTO patchRegistration(UUID id, PatientHospitalRegistrationRequestDTO dto);

    void deregisterPatient(UUID id);

    @Transactional(readOnly = true)
    List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatient(String patientUsername, int page, int size, Boolean active);

    /**
     * @deprecated prefer {@link #getRegistrationsByPatient(UUID, int, int, Boolean)} with explicit patient identifiers.
     */
    @Deprecated(since = "2025", forRemoval = true)
    List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatient(String patientUsername);

    /**
     * @deprecated prefer {@link #updateRegistration(UUID, PatientHospitalRegistrationRequestDTO)}.
     */
    @Deprecated(since = "2025", forRemoval = true)
    PatientHospitalRegistrationResponseDTO updateRegistration(String mrn, PatientHospitalRegistrationRequestDTO dto);

    @Transactional
    PatientHospitalRegistrationResponseDTO patchRegistration(String mrn, PatientHospitalRegistrationRequestDTO dto);

    /**
     * @deprecated MRN-based deregistration remains for legacy integrations only.
     */
    @Deprecated(since = "2025", forRemoval = true)
    void deregisterPatient(String mrn);
    PatientHospitalRegistrationResponseDTO getById(UUID id);

    List<PatientHospitalRegistrationResponseDTO> getRegistrationsByHospital(
        UUID hospitalId, int page, int size, Boolean active);

    @Transactional(readOnly = true)
    List<PatientMultiHospitalSummaryDTO> getPatientsRegisteredInMultipleHospitals();
}
