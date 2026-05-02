package com.example.hms.service;

import com.example.hms.enums.RefillStatus;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.RefillDecisionRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * Doctor-facing service for triaging patient-initiated medication refill requests.
 * Pairs with the patient-side flow in {@link PatientPortalService}.
 */
public interface RefillApprovalService {

    Page<MedicationRefillResponseDTO> listForProvider(Authentication auth,
                                                      RefillStatus status,
                                                      Pageable pageable);

    long countPendingForProvider(Authentication auth);

    MedicationRefillResponseDTO approve(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision);

    MedicationRefillResponseDTO reject(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision);
}
