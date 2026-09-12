package com.example.hms.service;

import com.example.hms.payload.dto.PatientConsentResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Read-only since E9 #65 (decision D7): the grant / revoke / active-check
 * operations are gone with the consent-grant model. The two listings stay
 * one release so the rows granted before the change remain visible.
 */
public interface PatientConsentService {

    /**
     * Returns all patient consents.
     */
    Page<PatientConsentResponseDTO> getAllConsents(Pageable pageable);

    /**
     * Returns all consents by patient.
     */
    Page<PatientConsentResponseDTO> getConsentsByPatient(UUID patientId, Pageable pageable);
}
