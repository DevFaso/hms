package com.example.hms.service;

import com.example.hms.enums.EligibilityCheckType;
import com.example.hms.enums.EligibilityScheme;
import com.example.hms.payload.dto.insurance.EligibilityCheckRequestDTO;
import com.example.hms.payload.dto.insurance.EligibilityResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/**
 * Real-time coverage / prior-auth gateway against West-African public-payer
 * schemes (NHIS, CNAMGS, mutuelle, …). Each call is persisted as a single
 * {@link com.example.hms.model.insurance.EligibilityCheck} row.
 *
 * <p>The actual partner-API connectors live behind
 * {@link com.example.hms.service.integration.eligibility.EligibilityProvider}
 * and ship today as deterministic stubs; full network integration is tracked
 * as P1 #12 follow-up #4.
 */
public interface EligibilityService {

    /** Run a coverage / prior-auth submission and persist the outcome. */
    EligibilityResponseDTO submit(EligibilityCheckRequestDTO request);

    /** Fetch a single persisted check (404s if not found). */
    EligibilityResponseDTO get(UUID checkId);

    /** Page through all checks for a patient, most-recent first. */
    Page<EligibilityResponseDTO> listByPatient(UUID patientId, Pageable pageable);

    /** Most-recent check for the (patient, scheme, type) tuple, if any. */
    Optional<EligibilityResponseDTO> findLatestForPatient(UUID patientId,
                                                          EligibilityScheme scheme,
                                                          EligibilityCheckType type);
}
