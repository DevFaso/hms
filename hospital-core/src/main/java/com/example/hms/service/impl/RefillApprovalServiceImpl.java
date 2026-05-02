package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.RefillDecisionRequestDTO;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.RefillApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefillApprovalServiceImpl implements RefillApprovalService {

    private static final String NOTIFICATION_TYPE = "MEDICATION_REFILL";

    private final RefillRequestRepository refillRequestRepository;
    private final StaffRepository staffRepository;
    private final NotificationService notificationService;
    private final ControllerAuthUtils authUtils;

    @Override
    @Transactional(readOnly = true)
    public Page<MedicationRefillResponseDTO> listForProvider(Authentication auth,
                                                             RefillStatus status,
                                                             Pageable pageable) {
        UUID staffId = resolveStaffId(auth);
        Page<RefillRequest> page = (status == null)
                ? refillRequestRepository.findByPrescription_Staff_Id(staffId, pageable)
                : refillRequestRepository.findByPrescription_Staff_IdAndStatus(staffId, status, pageable);
        return page.map(this::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public long countPendingForProvider(Authentication auth) {
        UUID staffId = resolveStaffId(auth);
        return refillRequestRepository.countByPrescription_Staff_IdAndStatus(staffId, RefillStatus.REQUESTED);
    }

    @Override
    @Transactional
    public MedicationRefillResponseDTO approve(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision) {
        return decide(auth, refillId, decision, RefillStatus.APPROVED);
    }

    @Override
    @Transactional
    public MedicationRefillResponseDTO reject(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision) {
        return decide(auth, refillId, decision, RefillStatus.DENIED);
    }

    private MedicationRefillResponseDTO decide(Authentication auth,
                                               UUID refillId,
                                               RefillDecisionRequestDTO decision,
                                               RefillStatus newStatus) {
        UUID staffId = resolveStaffId(auth);

        RefillRequest refill = refillRequestRepository.findById(refillId)
                .orElseThrow(() -> new ResourceNotFoundException("Refill request not found"));

        Prescription prescription = refill.getPrescription();
        Staff prescriber = prescription != null ? prescription.getStaff() : null;
        if (prescriber == null || !staffId.equals(prescriber.getId())) {
            throw new AccessDeniedException("You can only act on refill requests for your own prescriptions.");
        }

        if (refill.getStatus() != RefillStatus.REQUESTED) {
            throw new BusinessException(
                "Only pending refill requests can be acted on. Current status: " + refill.getStatus());
        }

        refill.setStatus(newStatus);
        if (decision != null && decision.getProviderNotes() != null && !decision.getProviderNotes().isBlank()) {
            refill.setProviderNotes(decision.getProviderNotes().trim());
        }
        refill = refillRequestRepository.save(refill);

        notifyPatient(refill, newStatus);
        log.info("Staff {} marked refill {} as {}", staffId, refillId, newStatus);
        return toResponseDTO(refill);
    }

    private UUID resolveStaffId(Authentication auth) {
        authUtils.requireAuth(auth);
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException("Unable to resolve user from authentication."));
        List<Staff> staffList = staffRepository.findByUserId(userId);
        if (staffList.isEmpty()) {
            throw new BusinessException("No staff record linked to your account.");
        }
        return staffList.get(0).getId();
    }

    private void notifyPatient(RefillRequest refill, RefillStatus status) {
        Patient patient = refill.getPatient();
        if (patient == null || patient.getUser() == null) {
            return;
        }
        String username = patient.getUser().getUsername();
        if (username == null || username.isBlank()) {
            return;
        }
        String medicationName = refill.getPrescription() != null && refill.getPrescription().getMedicationName() != null
                ? refill.getPrescription().getMedicationName()
                : "your prescription";
        String verb = status == RefillStatus.APPROVED ? "approved" : "denied";
        String message = "Your refill request for " + medicationName + " has been " + verb + ".";
        try {
            notificationService.createNotification(message, username, NOTIFICATION_TYPE);
        } catch (Exception ex) {
            log.warn("Failed to deliver refill decision notification to {} for refill {}",
                    username, refill.getId());
        }
    }

    private MedicationRefillResponseDTO toResponseDTO(RefillRequest r) {
        String medName = null;
        if (r.getPrescription() != null && r.getPrescription().getMedicationName() != null) {
            medName = r.getPrescription().getMedicationName();
        }
        return MedicationRefillResponseDTO.builder()
                .id(r.getId())
                .prescriptionId(r.getPrescription() != null ? r.getPrescription().getId() : null)
                .medicationName(medName)
                .patientId(r.getPatient() != null ? r.getPatient().getId() : null)
                .status(r.getStatus() != null ? r.getStatus().name() : null)
                .preferredPharmacy(r.getPreferredPharmacy())
                .notes(r.getPatientNotes())
                .providerNotes(r.getProviderNotes())
                .requestedAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
