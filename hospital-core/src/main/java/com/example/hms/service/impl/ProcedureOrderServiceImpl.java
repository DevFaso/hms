package com.example.hms.service.impl;

import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.procedure.ProcedureOrderRequestDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderUpdateDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.ProcedureOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProcedureOrderServiceImpl implements ProcedureOrderService {

    private final ProcedureOrderRepository procedureOrderRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;

    @Override
    public ProcedureOrderResponseDTO createProcedureOrder(ProcedureOrderRequestDTO request, UUID orderingProviderId) {
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + request.getPatientId()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + request.getHospitalId()));

        Staff orderingProvider = staffRepository.findById(orderingProviderId)
            .orElseThrow(() -> new ResourceNotFoundException("Ordering provider not found with ID: " + orderingProviderId));

        Encounter encounter = null;
        if (request.getEncounterId() != null) {
            encounter = encounterRepository.findById(request.getEncounterId())
                .orElseThrow(() -> new ResourceNotFoundException("Encounter not found with ID: " + request.getEncounterId()));
        }

        ProcedureOrder procedureOrder = ProcedureOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .orderingProvider(orderingProvider)
            .encounter(encounter)
            .procedureCode(request.getProcedureCode())
            .procedureName(request.getProcedureName())
            .procedureCategory(request.getProcedureCategory())
            .indication(request.getIndication())
            .clinicalNotes(request.getClinicalNotes())
            .urgency(request.getUrgency())
            .status(ProcedureOrderStatus.ORDERED)
            .scheduledDatetime(request.getScheduledDatetime())
            .estimatedDurationMinutes(request.getEstimatedDurationMinutes())
            .requiresAnesthesia(request.getRequiresAnesthesia() != null ? request.getRequiresAnesthesia() : Boolean.FALSE)
            .anesthesiaType(request.getAnesthesiaType())
            .requiresSedation(request.getRequiresSedation() != null ? request.getRequiresSedation() : Boolean.FALSE)
            .sedationType(request.getSedationType())
            .preProcedureInstructions(request.getPreProcedureInstructions())
            .consentObtained(request.getConsentObtained() != null ? request.getConsentObtained() : Boolean.FALSE)
            .consentObtainedAt(request.getConsentObtainedAt())
            .consentObtainedBy(request.getConsentObtainedBy())
            .consentFormLocation(request.getConsentFormLocation())
            .laterality(request.getLaterality())
            .siteMarked(request.getSiteMarked() != null ? request.getSiteMarked() : Boolean.FALSE)
            .specialEquipmentNeeded(request.getSpecialEquipmentNeeded())
            .bloodProductsRequired(request.getBloodProductsRequired() != null ? request.getBloodProductsRequired() : Boolean.FALSE)
            .imagingGuidanceRequired(request.getImagingGuidanceRequired() != null ? request.getImagingGuidanceRequired() : Boolean.FALSE)
            .orderedAt(LocalDateTime.now())
            .build();

        ProcedureOrder saved = procedureOrderRepository.save(procedureOrder);
        log.info("Created procedure order ID {} for patient {} - Procedure: {}", 
            saved.getId(), patient.getId(), request.getProcedureName());

        return toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ProcedureOrderResponseDTO getProcedureOrder(UUID orderId) {
        ProcedureOrder procedureOrder = getProcedureOrderEntity(orderId);
        return toResponseDTO(procedureOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcedureOrderResponseDTO> getProcedureOrdersForPatient(UUID patientId) {
        List<ProcedureOrder> orders = procedureOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId);
        return orders.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcedureOrderResponseDTO> getProcedureOrdersForHospital(UUID hospitalId, ProcedureOrderStatus status) {
        List<ProcedureOrder> orders;
        if (status != null) {
            orders = procedureOrderRepository.findByHospital_IdAndStatusOrderByScheduledDatetimeAsc(hospitalId, status);
        } else {
            orders = procedureOrderRepository.findAll().stream()
                .filter(order -> order.getHospital().getId().equals(hospitalId))
                .toList();
        }
        return orders.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcedureOrderResponseDTO> getProcedureOrdersOrderedBy(UUID providerId) {
        List<ProcedureOrder> orders = procedureOrderRepository.findByOrderingProvider_IdOrderByOrderedAtDesc(providerId);
        return orders.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcedureOrderResponseDTO> getProcedureOrdersScheduledBetween(UUID hospitalId, LocalDateTime startDate, LocalDateTime endDate) {
        List<ProcedureOrder> orders = procedureOrderRepository.findByHospital_IdAndScheduledDatetimeBetween(hospitalId, startDate, endDate);
        return orders.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    public ProcedureOrderResponseDTO updateProcedureOrder(UUID orderId, ProcedureOrderUpdateDTO updateDTO) {
        ProcedureOrder procedureOrder = getProcedureOrderEntity(orderId);

        if (procedureOrder.getStatus() == ProcedureOrderStatus.COMPLETED) {
            throw new BusinessException("Cannot update a completed procedure order");
        }

        if (procedureOrder.getStatus() == ProcedureOrderStatus.CANCELLED) {
            throw new BusinessException("Cannot update a cancelled procedure order");
        }

        if (updateDTO.getStatus() != null) {
            procedureOrder.setStatus(updateDTO.getStatus());
            if (updateDTO.getStatus() == ProcedureOrderStatus.COMPLETED) {
                procedureOrder.setCompletedAt(LocalDateTime.now());
            }
        }

        if (updateDTO.getScheduledDatetime() != null) {
            procedureOrder.setScheduledDatetime(updateDTO.getScheduledDatetime());
            if (procedureOrder.getStatus() == ProcedureOrderStatus.ORDERED) {
                procedureOrder.setStatus(ProcedureOrderStatus.SCHEDULED);
            }
        }

        if (updateDTO.getConsentObtained() != null) {
            applyConsentUpdate(procedureOrder, updateDTO);
        }

        if (updateDTO.getSiteMarked() != null) {
            procedureOrder.setSiteMarked(updateDTO.getSiteMarked());
        }

        ProcedureOrder saved = procedureOrderRepository.save(procedureOrder);
        return toResponseDTO(saved);
    }

    @Override
    public ProcedureOrderResponseDTO cancelProcedureOrder(UUID orderId, String cancellationReason) {
        ProcedureOrder procedureOrder = getProcedureOrderEntity(orderId);

        if (procedureOrder.getStatus() == ProcedureOrderStatus.COMPLETED) {
            throw new BusinessException("Cannot cancel a completed procedure order");
        }

        if (procedureOrder.getStatus() == ProcedureOrderStatus.CANCELLED) {
            throw new BusinessException("Procedure order is already cancelled");
        }

        procedureOrder.setStatus(ProcedureOrderStatus.CANCELLED);
        procedureOrder.setCancelledAt(LocalDateTime.now());
        procedureOrder.setCancellationReason(cancellationReason);

        ProcedureOrder saved = procedureOrderRepository.save(procedureOrder);
        log.info("Procedure order {} cancelled: {}", orderId, cancellationReason);

        return toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcedureOrderResponseDTO> getPendingConsentOrders(UUID hospitalId) {
        List<ProcedureOrder> orders = procedureOrderRepository.findByStatusAndConsentObtainedFalse(ProcedureOrderStatus.SCHEDULED);
        return orders.stream()
            .filter(order -> order.getHospital().getId().equals(hospitalId))
            .map(this::toResponseDTO)
            .toList();
    }

    // Helper methods

    private ProcedureOrder getProcedureOrderEntity(UUID orderId) {
        return procedureOrderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Procedure order not found with ID: " + orderId));
    }

    private ProcedureOrderResponseDTO toResponseDTO(ProcedureOrder order) {
        UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
        String patientMrn = null;
        if (order.getPatient() != null && hospitalId != null) {
            patientMrn = order.getPatient().getMrnForHospital(hospitalId);
        }
        
        return ProcedureOrderResponseDTO.builder()
            .id(order.getId())
            .patientId(order.getPatient() != null ? order.getPatient().getId() : null)
            .patientName(order.getPatient() != null ? order.getPatient().getFullName() : null)
            .patientMrn(patientMrn)
            .hospitalId(hospitalId)
            .hospitalName(order.getHospital() != null ? order.getHospital().getName() : null)
            .orderingProviderId(order.getOrderingProvider() != null ? order.getOrderingProvider().getId() : null)
            .orderingProviderName(order.getOrderingProvider() != null ? order.getOrderingProvider().getFullName() : null)
            .encounterId(order.getEncounter() != null ? order.getEncounter().getId() : null)
            .procedureCode(order.getProcedureCode())
            .procedureName(order.getProcedureName())
            .procedureCategory(order.getProcedureCategory())
            .indication(order.getIndication())
            .clinicalNotes(order.getClinicalNotes())
            .urgency(order.getUrgency())
            .status(order.getStatus())
            .scheduledDatetime(order.getScheduledDatetime())
            .estimatedDurationMinutes(order.getEstimatedDurationMinutes())
            .requiresAnesthesia(order.getRequiresAnesthesia())
            .anesthesiaType(order.getAnesthesiaType())
            .requiresSedation(order.getRequiresSedation())
            .sedationType(order.getSedationType())
            .preProcedureInstructions(order.getPreProcedureInstructions())
            .consentObtained(order.getConsentObtained())
            .consentObtainedAt(order.getConsentObtainedAt())
            .consentObtainedBy(order.getConsentObtainedBy())
            .consentFormLocation(order.getConsentFormLocation())
            .laterality(order.getLaterality())
            .siteMarked(order.getSiteMarked())
            .specialEquipmentNeeded(order.getSpecialEquipmentNeeded())
            .bloodProductsRequired(order.getBloodProductsRequired())
            .imagingGuidanceRequired(order.getImagingGuidanceRequired())
            .orderedAt(order.getOrderedAt())
            .cancelledAt(order.getCancelledAt())
            .cancellationReason(order.getCancellationReason())
            .completedAt(order.getCompletedAt())
            .createdAt(order.getCreatedAt())
            .updatedAt(order.getUpdatedAt())
            .build();
    }

    private void applyConsentUpdate(ProcedureOrder order, ProcedureOrderUpdateDTO dto) {
        order.setConsentObtained(dto.getConsentObtained());
        if (dto.getConsentObtainedAt() != null) {
            order.setConsentObtainedAt(dto.getConsentObtainedAt());
        }
        if (dto.getConsentObtainedBy() != null) {
            order.setConsentObtainedBy(dto.getConsentObtainedBy());
        }
        if (dto.getConsentFormLocation() != null) {
            order.setConsentFormLocation(dto.getConsentFormLocation());
        }
    }
}
