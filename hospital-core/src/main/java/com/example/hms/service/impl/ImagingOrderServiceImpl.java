package com.example.hms.service.impl;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ImagingOrderMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.imaging.ImagingOrderDuplicateMatchDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderSignatureRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderStatusUpdateRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.ImagingOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ImagingOrderServiceImpl implements ImagingOrderService {

    private static final int DEFAULT_DUPLICATE_LOOKBACK_DAYS = 30;

    private final ImagingOrderRepository imagingOrderRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final ImagingOrderMapper imagingOrderMapper;

    @Override
    public ImagingOrderResponseDTO createOrder(ImagingOrderRequestDTO request, UUID orderingUserId) {
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + request.getPatientId()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + request.getHospitalId()));

        ImagingOrder imagingOrder = imagingOrderMapper.toEntity(request, patient, hospital);
        imagingOrder.setOrderedAt(LocalDateTime.now());
        if (orderingUserId != null) {
            imagingOrder.setOrderingProviderUserId(orderingUserId);
        }

        List<ImagingOrder> duplicateMatches = loadDuplicateMatches(patient.getId(), request.getModality(), request.getBodyRegion(), request.getDuplicateLookbackDays());
        if (!duplicateMatches.isEmpty()) {
            imagingOrder.setDuplicateOfRecentOrder(true);
            imagingOrder.setDuplicateReferenceOrderId(duplicateMatches.get(0).getId());
        } else {
            imagingOrder.setDuplicateOfRecentOrder(false);
            imagingOrder.setDuplicateReferenceOrderId(null);
        }

        ImagingOrder saved = imagingOrderRepository.save(imagingOrder);
        return imagingOrderMapper.toResponseDTO(saved, duplicateMatches);
    }

    @Override
    public ImagingOrderResponseDTO updateOrder(UUID orderId, ImagingOrderRequestDTO request) {
        ImagingOrder order = getOrderEntity(orderId);

        if (request.getPatientId() != null && (order.getPatient() == null || !request.getPatientId().equals(order.getPatient().getId()))) {
            Patient patient = patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + request.getPatientId()));
            order.setPatient(patient);
        }

        if (request.getHospitalId() != null && (order.getHospital() == null || !request.getHospitalId().equals(order.getHospital().getId()))) {
            Hospital hospital = hospitalRepository.findById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + request.getHospitalId()));
            order.setHospital(hospital);
        }

        imagingOrderMapper.updateEntityFromRequest(order, request);

        List<ImagingOrder> duplicateMatches = loadDuplicateMatches(
            order.getPatient() != null ? order.getPatient().getId() : null,
            order.getModality(),
            order.getBodyRegion(),
            request.getDuplicateLookbackDays()
        );
        if (!duplicateMatches.isEmpty()) {
            order.setDuplicateOfRecentOrder(true);
            order.setDuplicateReferenceOrderId(duplicateMatches.get(0).getId());
        } else {
            order.setDuplicateOfRecentOrder(false);
            order.setDuplicateReferenceOrderId(null);
        }

        ImagingOrder saved = imagingOrderRepository.save(order);
        return imagingOrderMapper.toResponseDTO(saved, duplicateMatches);
    }

    @Override
    public ImagingOrderResponseDTO updateOrderStatus(UUID orderId, ImagingOrderStatusUpdateRequestDTO request) {
        ImagingOrder order = getOrderEntity(orderId);

        order.setStatus(request.getStatus());
        order.setScheduledDate(request.getScheduledDate());
        order.setScheduledTime(request.getScheduledTime());
        order.setAppointmentLocation(request.getAppointmentLocation());
        order.setWorkflowNotes(request.getWorkflowNotes());
        order.setRequiresAuthorization(request.getRequiresAuthorization());
        order.setAuthorizationNumber(request.getAuthorizationNumber());
        order.setStatusUpdatedAt(LocalDateTime.now());
        order.setStatusUpdatedBy(request.getPerformedByUserId());

        if (request.getStatus() == ImagingOrderStatus.CANCELLED) {
            order.setCancellationReason(request.getCancellationReason());
            order.setCancelledAt(LocalDateTime.now());
            order.setCancelledByUserId(request.getPerformedByUserId());
            order.setCancelledByName(request.getPerformedByName());
        }

        ImagingOrder saved = imagingOrderRepository.save(order);
        return imagingOrderMapper.toResponseDTO(saved);
    }

    @Override
    public ImagingOrderResponseDTO captureProviderSignature(UUID orderId, ImagingOrderSignatureRequestDTO request) {
        ImagingOrder order = getOrderEntity(orderId);
        order.setOrderingProviderName(request.getProviderName());
        order.setOrderingProviderNpi(request.getProviderNpi());
        order.setOrderingProviderUserId(request.getProviderUserId());
        order.setProviderSignatureStatement(request.getSignatureStatement());
        order.setProviderSignedAt(request.getSignedAt() != null ? request.getSignedAt() : LocalDateTime.now());
        order.setAttestationConfirmed(request.getAttestationConfirmed());
        if (order.getStatus() == ImagingOrderStatus.DRAFT) {
            order.setStatus(ImagingOrderStatus.ORDERED);
        }

        ImagingOrder saved = imagingOrderRepository.save(order);
        return imagingOrderMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ImagingOrderResponseDTO getOrder(UUID orderId) {
        ImagingOrder order = getOrderEntity(orderId);
        return imagingOrderMapper.toResponseDTO(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImagingOrderResponseDTO> getOrdersByPatient(UUID patientId, ImagingOrderStatus status) {
        List<ImagingOrder> orders;
        if (status != null) {
            orders = imagingOrderRepository.findByPatient_IdAndStatusOrderByOrderedAtDesc(patientId, status);
        } else {
            orders = imagingOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId);
        }
        return orders.stream()
            .map(imagingOrderMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImagingOrderResponseDTO> getOrdersByHospital(UUID hospitalId, ImagingOrderStatus status) {
        List<ImagingOrder> orders;
        if (status != null) {
            orders = imagingOrderRepository.findByHospital_IdAndStatusInOrderByOrderedAtDesc(hospitalId, List.of(status));
        } else {
            orders = imagingOrderRepository.findByHospital_IdOrderByOrderedAtDesc(hospitalId);
        }
        return orders.stream()
            .map(imagingOrderMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImagingOrderDuplicateMatchDTO> previewDuplicates(UUID patientId, ImagingModality modality, String bodyRegion, Integer lookbackDays) {
        if (patientId == null || modality == null) {
            return Collections.emptyList();
        }
        List<ImagingOrder> matches = loadDuplicateMatches(patientId, modality, bodyRegion, lookbackDays);
        return matches.stream()
            .map(imagingOrderMapper::toDuplicateMatchDTO)
            .toList();
    }

    private ImagingOrder getOrderEntity(UUID orderId) {
        return imagingOrderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Imaging order not found with ID: " + orderId));
    }

    private List<ImagingOrder> loadDuplicateMatches(UUID patientId, ImagingModality modality, String bodyRegion, Integer lookbackDays) {
        if (patientId == null || modality == null) {
            return Collections.emptyList();
        }
        int window = (lookbackDays == null || lookbackDays <= 0) ? DEFAULT_DUPLICATE_LOOKBACK_DAYS : lookbackDays;
        LocalDateTime orderedAfter = LocalDateTime.now().minusDays(window);
        String normalizedBodyRegion = normalizeBodyRegion(bodyRegion);
        return imagingOrderRepository.findPotentialDuplicates(patientId, modality, normalizedBodyRegion, orderedAfter);
    }

    private String normalizeBodyRegion(String bodyRegion) {
        if (bodyRegion == null) {
            return null;
        }
        String trimmed = bodyRegion.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
