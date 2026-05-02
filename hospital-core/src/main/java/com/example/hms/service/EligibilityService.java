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

    /**
     * Page through all checks for a patient, most-recent first. When
     * {@code hospitalId} is non-null the result is scoped to that hospital so
     * a clinician at hospital A cannot see hospital B's eligibility history
     * for the same patient. {@code hospitalId == null} returns all hospitals
     * (intended for SUPER_ADMIN, who is allowed to query unscoped).
     */
    Page<EligibilityResponseDTO> listByPatient(UUID patientId, UUID hospitalId, Pageable pageable);

    /**
     * Most-recent check for the (patient, scheme, type) tuple, optionally
     * scoped to a hospital. Same null-means-unscoped semantics as
     * {@link #listByPatient}.
     */
    Optional<EligibilityResponseDTO> findLatestForPatient(UUID patientId,
                                                          UUID hospitalId,
                                                          EligibilityScheme scheme,
                                                          EligibilityCheckType type);
}
