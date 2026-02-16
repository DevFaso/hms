package com.example.hms.service;

import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.PatientConsentRequestDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PatientConsentService {

    /**
     * Grants patient consent using the full DTO structure.
     */
    PatientConsentResponseDTO grantConsentWithDetails(
            PatientConsentRequestDTO requestDTO,
            PatientResponseDTO patientDTO,
            HospitalResponseDTO fromHospitalDTO,
            HospitalResponseDTO toHospitalDTO
    );

    /**
     * Grants consent for sharing patient data from one hospital to another.
     */
    PatientConsentResponseDTO grantConsent(PatientConsentRequestDTO requestDTO);

    /**
     * Revokes consent previously granted.
     */
    void revokeConsent(UUID patientId, UUID fromHospitalId, UUID toHospitalId);

    /**
     * Returns all patient consents.
     */
    Page<PatientConsentResponseDTO> getAllConsents(Pageable pageable);

    /**
     * Returns all consents by patient.
     */
    Page<PatientConsentResponseDTO> getConsentsByPatient(UUID patientId, Pageable pageable);

    /**
     * Returns all consents from a given hospital.
     */
    Page<PatientConsentResponseDTO> getConsentsByFromHospital(UUID fromHospitalId, Pageable pageable);

    /**
     * Returns all consents to a given hospital.
     */
    Page<PatientConsentResponseDTO> getConsentsByToHospital(UUID toHospitalId, Pageable pageable);

    /**
     * Checks if a consent is active between hospitals for a patient.
     */
    boolean isConsentActive(UUID patientId, UUID fromHospitalId, UUID toHospitalId);
}
