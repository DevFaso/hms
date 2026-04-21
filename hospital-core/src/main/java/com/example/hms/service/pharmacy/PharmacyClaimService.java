package com.example.hms.service.pharmacy;

import com.example.hms.payload.dto.pharmacy.PharmacyClaimRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * T-47/T-49: pharmacy insurance-claim workflow. Covers draft creation,
 * submission, status transitions (ACCEPTED / REJECTED / PAID) and reads.
 */
public interface PharmacyClaimService {

    PharmacyClaimResponseDTO createClaim(PharmacyClaimRequestDTO dto);

    PharmacyClaimResponseDTO submitClaim(UUID id);

    PharmacyClaimResponseDTO markAccepted(UUID id, String notes);

    PharmacyClaimResponseDTO markRejected(UUID id, String rejectionReason);

    PharmacyClaimResponseDTO markPaid(UUID id, String notes);

    PharmacyClaimResponseDTO getClaim(UUID id);

    Page<PharmacyClaimResponseDTO> listByHospital(Pageable pageable);

    Page<PharmacyClaimResponseDTO> listByDispense(UUID dispenseId, Pageable pageable);

    Page<PharmacyClaimResponseDTO> listByPatient(UUID patientId, Pageable pageable);
}
