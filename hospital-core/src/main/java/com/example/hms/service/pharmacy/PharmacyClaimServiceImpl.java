package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.PharmacyClaimMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.PharmacyClaim;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * T-47 / T-49 / T-51: pharmacy claim service.
 * All writes are scoped to the caller's active hospital and emit
 * {@link AuditEventType#CLAIM_SUBMITTED} audit entries on status transitions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PharmacyClaimServiceImpl implements PharmacyClaimService {

    private static final String AUDIT_ENTITY = "PHARMACY_CLAIM";

    private final PharmacyClaimRepository claimRepository;
    private final DispenseRepository dispenseRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final PharmacyClaimMapper claimMapper;
    private final RoleValidator roleValidator;
    private final PharmacyServiceSupport support;

    @Override
    @Transactional
    public PharmacyClaimResponseDTO createClaim(PharmacyClaimRequestDTO dto) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        validateRequest(dto);

        // SUPER_ADMIN may have no active hospital; require one to create a claim.
        if (hospitalId == null) {
            throw new BusinessException("Active hospital context is required to create a claim");
        }
        if (!hospitalId.equals(dto.getHospitalId())) {
            throw new BusinessException("Claim hospital does not match the active hospital");
        }

        Dispense dispense = dispenseRepository.findById(dto.getDispenseId())
                .orElseThrow(() -> new ResourceNotFoundException("dispense.notfound"));
        if (dispense.getPatient() == null
                || !dispense.getPatient().getId().equals(dto.getPatientId())) {
            throw new BusinessException("Patient does not match the dispense record");
        }
        if (dispense.getPharmacy() == null
                || dispense.getPharmacy().getHospital() == null
                || !hospitalId.equals(dispense.getPharmacy().getHospital().getId())) {
            throw new ResourceNotFoundException("dispense.notfound");
        }

        Patient patient = patientRepository.findById(dto.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", dto.getPatientId()));
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));

        PharmacyClaim entity = claimMapper.toEntity(dto, dispense, patient, hospital);
        if (entity.getClaimStatus() == null) {
            entity.setClaimStatus(PharmacyClaimStatus.DRAFT);
        }
        PharmacyClaim saved = claimRepository.save(entity);

        support.logAudit(AuditEventType.CLAIM_SUBMITTED,
                "Pharmacy claim created (" + saved.getClaimStatus() + ") amount "
                        + saved.getAmount() + " " + saved.getCurrency()
                        + " for dispense " + dispense.getId(),
                saved.getId().toString(),
                AUDIT_ENTITY);

        return claimMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public PharmacyClaimResponseDTO submitClaim(UUID id) {
        PharmacyClaim claim = loadInScope(id);
        if (claim.getClaimStatus() != PharmacyClaimStatus.DRAFT) {
            throw new BusinessException("Only DRAFT claims can be submitted");
        }
        User currentUser = resolveCurrentUser();
        claim.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        claim.setSubmittedAt(LocalDateTime.now());
        claim.setSubmittedByUser(currentUser);
        PharmacyClaim saved = claimRepository.save(claim);

        support.logAudit(AuditEventType.CLAIM_SUBMITTED,
                "Pharmacy claim submitted to payer; amount "
                        + saved.getAmount() + " " + saved.getCurrency(),
                saved.getId().toString(),
                AUDIT_ENTITY);
        return claimMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public PharmacyClaimResponseDTO markAccepted(UUID id, String notes) {
        return transition(id, PharmacyClaimStatus.ACCEPTED,
                EnumSet.of(PharmacyClaimStatus.SUBMITTED),
                notes, null, "accepted");
    }

    @Override
    @Transactional
    public PharmacyClaimResponseDTO markRejected(UUID id, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new BusinessException("Rejection reason is required");
        }
        return transition(id, PharmacyClaimStatus.REJECTED,
                EnumSet.of(PharmacyClaimStatus.SUBMITTED),
                null, rejectionReason, "rejected");
    }

    @Override
    @Transactional
    public PharmacyClaimResponseDTO markPaid(UUID id, String notes) {
        return transition(id, PharmacyClaimStatus.PAID,
                EnumSet.of(PharmacyClaimStatus.ACCEPTED),
                notes, null, "paid");
    }

    private PharmacyClaimResponseDTO transition(UUID id,
                                                PharmacyClaimStatus target,
                                                Set<PharmacyClaimStatus> allowedFrom,
                                                String notes,
                                                String rejectionReason,
                                                String verb) {
        PharmacyClaim claim = loadInScope(id);
        if (!allowedFrom.contains(claim.getClaimStatus())) {
            throw new BusinessException("Cannot mark " + verb
                    + " from status " + claim.getClaimStatus());
        }
        claim.setClaimStatus(target);
        if (notes != null && !notes.isBlank()) {
            claim.setNotes(notes);
        }
        if (rejectionReason != null) {
            claim.setRejectionReason(rejectionReason);
        }
        PharmacyClaim saved = claimRepository.save(claim);

        support.logAudit(AuditEventType.CLAIM_SUBMITTED,
                "Pharmacy claim " + verb + " (status=" + target + ")",
                saved.getId().toString(),
                AUDIT_ENTITY);
        return claimMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PharmacyClaimResponseDTO getClaim(UUID id) {
        return claimMapper.toResponseDTO(loadInScope(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyClaimResponseDTO> listByHospital(Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // SUPER_ADMIN unscoped read — return claims across hospitals.
            return claimRepository.findAll(pageable).map(claimMapper::toResponseDTO);
        }
        return claimRepository.findByHospitalId(hospitalId, pageable).map(claimMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyClaimResponseDTO> listByDispense(UUID dispenseId, Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return claimRepository.findByDispenseId(dispenseId, pageable).map(claimMapper::toResponseDTO);
        }
        return claimRepository.findByDispenseIdAndHospitalId(dispenseId, hospitalId, pageable)
                .map(claimMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyClaimResponseDTO> listByPatient(UUID patientId, Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return claimRepository.findByPatientId(patientId, pageable).map(claimMapper::toResponseDTO);
        }
        return claimRepository.findByPatientIdAndHospitalId(patientId, hospitalId, pageable)
                .map(claimMapper::toResponseDTO);
    }

    /**
     * Patient self-service read path: does NOT call {@link RoleValidator#requireActiveHospitalId()}
     * because a patient caller has no staff hospital assignment. The caller (patient portal
     * controller) is responsible for verifying that {@code patientId} is the authenticated
     * patient's own ID before invoking this method.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyClaimResponseDTO> listByPatientForSelf(UUID patientId, Pageable pageable) {
        return claimRepository.findByPatientId(patientId, pageable).map(claimMapper::toResponseDTO);
    }

    private PharmacyClaim loadInScope(UUID id) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        PharmacyClaim claim = claimRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.claim.notfound"));
        // SUPER_ADMIN without an active hospital may read across hospitals; otherwise enforce tenant scope.
        if (hospitalId != null
                && (claim.getHospital() == null || !hospitalId.equals(claim.getHospital().getId()))) {
            throw new ResourceNotFoundException("pharmacy.claim.notfound");
        }
        return claim;
    }

    private User resolveCurrentUser() {
        UUID uid = roleValidator.getCurrentUserId();
        if (uid == null) {
            throw new BusinessException("Unable to determine current user");
        }
        return userRepository.findById(uid)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    private void validateRequest(PharmacyClaimRequestDTO dto) {
        if (dto == null) {
            throw new BusinessException("Claim request is required");
        }
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Claim amount must be positive");
        }
    }
}
