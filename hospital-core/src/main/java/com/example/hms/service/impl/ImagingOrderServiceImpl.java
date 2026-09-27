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
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.ImagingOrderService;
import com.example.hms.service.PatientSubjectReadGuard;
import com.example.hms.service.PatientSubjectReaderRoles;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class ImagingOrderServiceImpl implements ImagingOrderService {

    private static final int DEFAULT_DUPLICATE_LOOKBACK_DAYS = 30;
    private static final String ORDER_NOT_FOUND = "Imaging order not found with ID: ";

    /**
     * Self-reference for proxy-routed internal calls
     * ({@link #getAllOrders(ImagingOrderStatus)}
     * → {@link #getOrdersByHospital(UUID, ImagingOrderStatus)}).
     * Sonar S6809 — see PatientServiceImpl.setSelf for the full
     * rationale and pattern docstring.
     */
    private ImagingOrderService self;

    @Autowired
    public void setSelf(@Lazy ImagingOrderService self) {
        this.self = self;
    }

    private final ImagingOrderRepository imagingOrderRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final ImagingOrderMapper imagingOrderMapper;
    private final RoleValidator roleValidator;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final PatientSubjectReadGuard subjectReadGuard;
    private final PatientHospitalRegistrationRepository registrationRepository;

    @Override
    public ImagingOrderResponseDTO createOrder(ImagingOrderRequestDTO request, UUID orderingUserId) {
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));
        requireActingHospital(request.getHospitalId());
        requirePatientRegisteredAtActingHospital(request.getPatientId());

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
        ImagingOrder order = getOrderInScope(orderId);

        if (request.getPatientId() != null && (order.getPatient() == null || !request.getPatientId().equals(order.getPatient().getId()))) {
            Patient patient = patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));
            requirePatientRegisteredAtActingHospital(request.getPatientId());
            order.setPatient(patient);
        }

        if (request.getHospitalId() != null && (order.getHospital() == null || !request.getHospitalId().equals(order.getHospital().getId()))) {
            // An order is never moved away from the hospital the caller acts
            // at: the portal only echoes the order's own hospital back, so a
            // different one is refused exactly as a missing hospital. A
            // verified super-admin in global view keeps the old behaviour.
            Hospital hospital = hospitalRepository.findById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));
            requireActingHospital(request.getHospitalId());
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
        ImagingOrder order = getOrderInScope(orderId);

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
        ImagingOrder order = getOrderInScope(orderId);
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
        return imagingOrderMapper.toResponseDTO(getOrderInScope(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImagingOrderResponseDTO> getOrdersByPatient(UUID patientId, ImagingOrderStatus status) {
        // A patient caller reads only their own. Another patient's id answers
        // exactly as an id that matches no row does -- an empty list -- and
        // before the hospital lookup below, which can answer differently.
        if (!subjectReadGuard.mayRead(PatientSubjectReaderRoles.IMAGING_ORDERS_BY_PATIENT, patientId)) {
            return List.of();
        }
        if (subjectReadGuard.ownsAsItsPatient(patientId)) {
            // Their own record, read as its patient wherever it was written (a
            // patient, or staff who are also this patient, #754's rule). Not a
            // disclosure: no reach recorded.
            List<ImagingOrder> own = status != null
                ? imagingOrderRepository.findByPatient_IdAndStatusOrderByOrderedAtDesc(patientId, status)
                : imagingOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId);
            return own.stream().map(imagingOrderMapper::toResponseDTO).toList();
        }
        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<ImagingOrder> orders;
        if (activeHospitalId != null) {
            // E9 #59b — imaging orders follow the patient: read across the
            // readable hospitals at the database, where this used to load the
            // patient's whole history and keep the acting hospital's rows in
            // memory. Untagged (V158), so every foreign row travels; accounted.
            UUID requesterUserId = roleValidator.getCurrentUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, activeHospitalId);
            orders = status != null
                ? imagingOrderRepository.findByPatient_IdAndHospital_IdInAndStatusOrderByOrderedAtDesc(patientId, readable, status)
                : imagingOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, readable);
            reachRecorder.recordReach(patientId, activeHospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(orders.stream().map(o -> CrossHospitalReachRecorder.hospitalIdOf(o.getHospital())).toList(), activeHospitalId),
                "Cross-hospital imaging order read on the treatment relationship");
        } else if (status != null) {
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
    public List<ImagingOrderResponseDTO> getAllOrders(ImagingOrderStatus status) {
        // ── Tenant isolation: non-superadmin scoped to active hospital ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            // Sonar S6809: route through the proxy so the inner
            // method's @Transactional(readOnly=true) is honored.
            return self.getOrdersByHospital(activeHospitalId, status);
        }
        List<ImagingOrder> orders;
        if (status != null) {
            orders = imagingOrderRepository.findByStatusOrderByOrderedAtDesc(status);
        } else {
            orders = imagingOrderRepository.findAllByOrderByOrderedAtDesc();
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
            .orElseThrow(() -> new ResourceNotFoundException(ORDER_NOT_FOUND + orderId));
    }

    /**
     * ── Tenant isolation ── the order, only when it belongs to the hospital
     * the caller acts at. The scoped {@code findById} is not enough on its
     * own: on the password login path the repository filter ORs in every
     * organisation the caller holds an assignment under, so a sibling
     * hospital's order loads. A foreign order is answered exactly as a
     * missing one. {@code requireActiveHospitalId} is null only for a
     * verified super-admin in global view, who keeps unrestricted access.
     */
    private ImagingOrder getOrderInScope(UUID orderId) {
        ImagingOrder order = getOrderEntity(orderId);
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && order.getHospital() != null
                && !activeHospitalId.equals(order.getHospital().getId())) {
            throw new ResourceNotFoundException(ORDER_NOT_FOUND + orderId);
        }
        return order;
    }

    /**
     * A write may only place an order at the hospital the caller acts at; any
     * other hospital is answered exactly as a missing one.
     */
    private void requireActingHospital(UUID hospitalId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && !activeHospitalId.equals(hospitalId)) {
            throw new ResourceNotFoundException("hospital.notFound", hospitalId);
        }
    }

    /**
     * The patient an order is placed for (or re-pointed at) must be registered
     * at the hospital the caller acts at — which, after the checks above, is
     * the order's hospital. The scoped patient {@code findById} is not enough:
     * the organisation disjunct admits a patient registered only at a sibling
     * hospital. Any registration counts, active or not, as for lab orders and
     * consultations. A foreign patient is answered exactly as a missing one; a
     * verified super-admin in global view is not held to a hospital.
     */
    private void requirePatientRegisteredAtActingHospital(UUID patientId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null
                && !registrationRepository.existsByPatientIdAndHospitalId(patientId, activeHospitalId)) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }
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
