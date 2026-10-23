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
    private final com.example.hms.repository.UserRepository userRepository;
    private final com.example.hms.repository.StaffRepository staffRepository;

    @Override
    public ImagingOrderResponseDTO createOrder(ImagingOrderRequestDTO request, UUID orderingUserId) {
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));

        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));
        requireActingHospital(request.getHospitalId());
        requirePatientRegisteredAtActingHospital(request.getPatientId());

        requireNotInThePast(request.getScheduledDate(), null);
        ImagingOrder imagingOrder = imagingOrderMapper.toEntity(request, patient, hospital);
        imagingOrder.setOrderedAt(LocalDateTime.now());
        // QA D302: the ordering provider is whoever is signed in. A free-text
        // name in the request is a claim, never shown as if a user had placed
        // the order; without a resolved user the order carries no provider.
        com.example.hms.model.User orderingUser = resolveOrderingUser(orderingUserId);
        imagingOrder.setOrderingProviderUserId(orderingUser != null ? orderingUser.getId() : null);
        imagingOrder.setOrderingProviderName(orderingUser != null ? displayName(orderingUser) : null);

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
    public ImagingOrderResponseDTO updateOrder(UUID orderId, ImagingOrderRequestDTO request, UUID callerUserId) {
        ImagingOrder order = getOrderInScope(orderId);
        requireEditableBy(order, callerUserId);

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
            Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));
            requireActingHospital(request.getHospitalId());
            order.setHospital(hospital);
        }

        requireNotInThePast(request.getScheduledDate(), order.getScheduledDate());
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

        requireNotInThePast(request.getScheduledDate(), order.getScheduledDate());
        order.setStatus(request.getStatus());
        order.setScheduledDate(request.getScheduledDate());
        order.setScheduledTime(request.getScheduledTime());
        order.setAppointmentLocation(request.getAppointmentLocation());
        order.setWorkflowNotes(request.getWorkflowNotes());
        order.setRequiresAuthorization(request.getRequiresAuthorization());
        order.setAuthorizationNumber(request.getAuthorizationNumber());
        // QA D503 class: who changed the status is the session, never the
        // performedBy fields of the request (kept on the DTO for old clients).
        UUID actorId = com.example.hms.security.CallerIdentity.currentUserId();
        com.example.hms.model.User actor = actorId == null ? null : userRepository.findById(actorId).orElse(null);
        order.setStatusUpdatedAt(LocalDateTime.now());
        order.setStatusUpdatedBy(actorId);

        if (request.getStatus() == ImagingOrderStatus.CANCELLED) {
            order.setCancellationReason(request.getCancellationReason());
            order.setCancelledAt(LocalDateTime.now());
            order.setCancelledByUserId(actorId);
            order.setCancelledByName(actor != null ? displayName(actor) : null);
        }

        ImagingOrder saved = imagingOrderRepository.save(order);
        return imagingOrderMapper.toResponseDTO(saved);
    }

    /**
     * QA D501: a signed order is immutable: its content is what the signer
     * attested, so an amendment is a cancellation and a new order. An unsigned
     * order is edited only by its ordering provider (a verified super-admin
     * excepted); an order with no recorded provider (written before D302)
     * keeps the old rule, any in-scope editor.
     */
    private void requireEditableBy(ImagingOrder order, UUID callerUserId) {
        if (order.getProviderSignedAt() != null) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("imaging.order.signedImmutable"));
        }
        UUID orderingProvider = order.getOrderingProviderUserId();
        if (orderingProvider != null && !orderingProvider.equals(callerUserId)
                && !com.example.hms.security.CallerIdentity.isGlobalSuperAdmin()) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("imaging.order.notOrderingProvider"));
        }
    }

    /**
     * QA D253: an exam cannot be booked for a day that has passed. A date the
     * order already carries is left alone, so a status change on a past
     * appointment (completing it, say) is not refused.
     */
    private com.example.hms.model.User resolveOrderingUser(UUID id) {
        if (id == null) {
            return null;
        }
        // The order-set dispatcher hands a staff id; the REST path a user id.
        return userRepository.findById(id)
            .orElseGet(() -> staffRepository.findById(id).map(com.example.hms.model.Staff::getUser).orElse(null));
    }

    private static String displayName(com.example.hms.model.User user) {
        String name = java.util.stream.Stream.of(user.getFirstName(), user.getLastName())
            .filter(part -> part != null && !part.isBlank())
            .collect(java.util.stream.Collectors.joining(" "));
        return name.isBlank() ? user.getUsername() : name;
    }

    static void requireNotInThePast(java.time.LocalDate requested, java.time.LocalDate current) {
        if (requested != null && !requested.equals(current) && requested.isBefore(java.time.LocalDate.now())) {
            throw new com.example.hms.exception.BusinessException("imaging.scheduledDate.past");
        }
    }

    @Override
    public ImagingOrderResponseDTO captureProviderSignature(UUID orderId, ImagingOrderSignatureRequestDTO request,
                                                            UUID signerUserId) {
        ImagingOrder order = getOrderInScope(orderId);
        // QA D302: the signer and the signing time come from the session. A
        // body naming someone else is refused; a body-supplied time is ignored.
        com.example.hms.model.User signer = signerUserId == null ? null
            : userRepository.findById(signerUserId).orElse(null);
        if (signer == null) {
            throw new org.springframework.security.access.AccessDeniedException("imaging.signature.signerUnknown");
        }
        if (request.getProviderUserId() != null && !request.getProviderUserId().equals(signer.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("imaging.signature.otherSigner");
        }
        // QA D407: a signed order keeps its signer. A second doctor's signature
        // used to overwrite the ordering provider and the signing time; the
        // signer's own repeat is a no-op, anyone else is refused. The order was
        // resolved in the caller's scope above, so the refusal leaks nothing.
        if (order.getProviderSignedAt() != null) {
            if (signer.getId().equals(order.getOrderingProviderUserId())) {
                return imagingOrderMapper.toResponseDTO(order);
            }
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("imaging.signature.alreadySigned"));
        }
        order.setOrderingProviderName(displayName(signer));
        order.setOrderingProviderNpi(request.getProviderNpi());
        order.setOrderingProviderUserId(signer.getId());
        order.setProviderSignatureStatement(request.getSignatureStatement());
        order.setProviderSignedAt(LocalDateTime.now());
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
        return imagingOrderMapper.toResponseDTO(getOrderReadableHere(orderId));
    }

    /**
     * QA D508 — a READ by id answers exactly as the by-patient list does: the
     * order is readable when its hospital is the acting one, or one the
     * record-access policy opens for this patient (E9 #59b, the same
     * {@code readableHospitalIds} the list filters on, so the opt-out,
     * restriction and break-the-glass rules are the list's). The list showed
     * hospital A's order to hospital B and the same id then answered 404.
     * Anything outside that set is still answered exactly as a missing order,
     * and every write keeps {@link #getOrderInScope}: reading across is not
     * acting across.
     */
    private ImagingOrder getOrderReadableHere(UUID orderId) {
        // QA D623: the scoped findById filtered the foreign row out before the
        // readable-hospitals rule below ever ran, so D508 never held for real.
        ImagingOrder order = imagingOrderRepository.findByIdUnscoped(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("imaging.order.notFound", orderId));
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID orderHospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
        if (activeHospitalId == null || orderHospitalId == null || activeHospitalId.equals(orderHospitalId)) {
            return order;
        }
        UUID patientId = order.getPatient() != null ? order.getPatient().getId() : null;
        UUID requesterUserId = roleValidator.getCurrentUserId();
        if (patientId == null
                || !recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, activeHospitalId)
                    .contains(orderHospitalId)) {
            throw new ResourceNotFoundException("imaging.order.notFound", orderId);
        }
        reachRecorder.recordReach(patientId, activeHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(List.of(orderHospitalId), activeHospitalId),
            "Cross-hospital imaging order read on the treatment relationship");
        return order;
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
            .orElseThrow(() -> new ResourceNotFoundException("imaging.order.notFound", orderId));
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
            throw new ResourceNotFoundException("imaging.order.notFound", orderId);
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
