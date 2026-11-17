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
    /** QA D1613: what a dispense costs and what the patient already paid for it. */
    private final com.example.hms.repository.pharmacy.InventoryItemRepository inventoryItemRepository;
    private final com.example.hms.repository.pharmacy.PharmacyPaymentRepository paymentRepository;
    private final RoleValidator roleValidator;
    private final PharmacyServiceSupport support;

    @Override
    @Transactional
    public PharmacyClaimResponseDTO createClaim(PharmacyClaimRequestDTO dto) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        validateRequest(dto);

        // SUPER_ADMIN may have no active hospital; require one to create a claim.
        if (hospitalId == null) {
            throw new BusinessException("pharmacy.claim.hospitalRequired");
        }
        if (!hospitalId.equals(dto.getHospitalId())) {
            throw new com.example.hms.exception.FieldBusinessException("hospitalId", "pharmacy.claim.hospitalMismatch");
        }

        // QA D1613: locked, so two claims for one dispense cannot both pass the live-claim check.
        Dispense dispense = dispenseRepository.findLockedById(dto.getDispenseId())
                .orElseThrow(() -> new ResourceNotFoundException("dispense.notfound"));
        if (dispense.getPatient() == null
                || !dispense.getPatient().getId().equals(dto.getPatientId())) {
            throw new com.example.hms.exception.FieldBusinessException("patientId", "pharmacy.claim.patientMismatch");
        }
        if (dispense.getPharmacy() == null
                || dispense.getPharmacy().getHospital() == null
                || !hospitalId.equals(dispense.getPharmacy().getHospital().getId())) {
            throw new ResourceNotFoundException("dispense.notfound");
        }

        requireClaimableDispense(dispense, dto.getAmount());

        Patient patient = patientRepository.findById(dto.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", dto.getPatientId()));
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", hospitalId));

        PharmacyClaim entity = claimMapper.toEntity(dto, dispense, patient, hospital);
        // QA D1613: a claim reaches SUBMITTED, ACCEPTED or PAID only through its transitions.
        entity.setClaimStatus(PharmacyClaimStatus.DRAFT);
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
            throw new BusinessException("pharmacy.claim.submitDraftOnly");
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
            // QA round 29 sweep (D1333 class): named, so the form marks the reason.
            throw new com.example.hms.exception.FieldBusinessException("rejectionReason",
                    "pharmacy.claim.rejectionReasonRequired");
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
            // QA D1000: both statuses read in the caller's language.
            throw new BusinessException("pharmacy.claim.transitionRefused",
                    com.example.hms.utility.MessageUtil.resolve("pharmacy.claim.status." + target.name()),
                    com.example.hms.utility.MessageUtil.resolve("pharmacy.claim.status." + claim.getClaimStatus().name()));
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
            throw new BusinessException("pharmacy.currentUserUnknown");
        }
        return userRepository.findById(uid)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    private void validateRequest(PharmacyClaimRequestDTO dto) {
        if (dto == null) {
            throw new BusinessException("pharmacy.claim.requestRequired");
        }
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new com.example.hms.exception.FieldBusinessException("amount", "pharmacy.claim.amountPositive");
        }
        // QA D1613: a new claim is a draft; a status sent with it used to be stored as is,
        // so a claim could be created already ACCEPTED or PAID.
        if (dto.getClaimStatus() != null && dto.getClaimStatus() != PharmacyClaimStatus.DRAFT) {
            throw new com.example.hms.exception.FieldBusinessException("claimStatus", "pharmacy.claim.startsAsDraft");
        }
    }

    /**
     * QA D1613 (round 44): a claim asks the payer for part of what a dispense
     * costs, so it is bounded by it. A 999 999 F CFA claim was accepted for a
     * 150 F CFA dispense, and one dispense carried two payable claims.
     *
     * <ul>
     *   <li>a cancelled dispense has nothing to claim;</li>
     *   <li>an unpriced one has no billable amount to bound the claim;</li>
     *   <li>the claim is at most the charge less what the patient already paid at the checkout;</li>
     *   <li>one live claim per dispense: a new one replaces only a REJECTED claim.</li>
     * </ul>
     * The caller holds the dispense lock, so two concurrent creates are serialised.
     */
    private void requireClaimableDispense(Dispense dispense, BigDecimal amount) {
        if (dispense.getStatus() == com.example.hms.enums.DispenseStatus.CANCELLED) {
            throw new com.example.hms.exception.FieldBusinessException("dispenseId", "pharmacy.claim.dispenseCancelled");
        }
        if (claimRepository.existsByDispenseIdAndClaimStatusNot(dispense.getId(), PharmacyClaimStatus.REJECTED)) {
            throw new com.example.hms.exception.FieldBusinessException("dispenseId", "pharmacy.claim.liveClaimExists");
        }
        BigDecimal charge = DispenseCharge.of(dispense, inventoryItemRepository)
                .orElseThrow(() -> new com.example.hms.exception.FieldBusinessException("dispenseId",
                        "pharmacy.claim.noPrice"));
        BigDecimal paid = java.util.Objects.requireNonNullElse(
                paymentRepository.sumPostedForDispense(dispense.getId()), BigDecimal.ZERO);
        BigDecimal billable = charge.subtract(paid).max(BigDecimal.ZERO);
        if (billable.signum() == 0) {
            throw new com.example.hms.exception.FieldBusinessException("dispenseId", "pharmacy.claim.nothingBillable");
        }
        if (amount.compareTo(billable) > 0) {
            throw new com.example.hms.exception.FieldBusinessException("amount", "pharmacy.claim.amountAboveBillable",
                    billable);
        }
    }
}
