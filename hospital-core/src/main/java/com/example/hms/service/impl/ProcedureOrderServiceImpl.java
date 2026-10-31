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
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.ProcedureOrderService;
import com.example.hms.service.PatientSubjectReadGuard;
import com.example.hms.service.PatientSubjectReaderRoles;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;

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
    private final RoleValidator roleValidator;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final PatientSubjectReadGuard subjectReadGuard;
    private final PatientHospitalRegistrationRepository registrationRepository;

    @Override
    public ProcedureOrderResponseDTO createProcedureOrder(ProcedureOrderRequestDTO request, UUID orderingProviderId) {
        // Placed only at the hospital the caller acts at (update and cancel are
        // already held there by requireInScope), for a patient registered
        // there. Either refusal answers exactly as the missing row does, and
        // both come before the patient is loaded.
        UUID actingHospitalId = roleValidator.requireActiveHospitalId();
        if (actingHospitalId != null) {
            if (!actingHospitalId.equals(request.getHospitalId())) {
                throw new ResourceNotFoundException("hospital.notFound", request.getHospitalId());
            }
            if (request.getPatientId() == null
                    || !registrationRepository.existsByPatientIdAndHospitalId(request.getPatientId(), actingHospitalId)) {
                throw new ResourceNotFoundException("patient.notFound", request.getPatientId());
            }
        }
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));

        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));

        // The controller hands over the caller's USER id; a staff id is still
        // accepted. One staff row per user (uq_staff_user), so the user's row
        // is found wherever it is filed.
        Staff orderingProvider = staffRepository.findById(orderingProviderId)
            .or(() -> staffRepository.findByUserIdAndHospitalId(orderingProviderId, hospital.getId()))
            .or(() -> staffRepository.findFirstByUserIdOrderByCreatedAtAsc(orderingProviderId))
            .orElseThrow(() -> new ResourceNotFoundException("procedureOrder.orderingProvider.notFound", orderingProviderId));

        // QA D857: a procedure is not planned for a time already past.
        requireNotInPast(request.getScheduledDatetime());

        Encounter encounter = null;
        if (request.getEncounterId() != null) {
            encounter = encounterRepository.findById(request.getEncounterId())
                .filter(e -> belongsTo(e, patient, hospital))
                .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound", request.getEncounterId()));
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
            // QA D880: an order placed with its date is already planned,
            // exactly as a later reschedule of an ORDERED order makes it.
            .status(request.getScheduledDatetime() != null ? ProcedureOrderStatus.SCHEDULED : ProcedureOrderStatus.ORDERED)
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
        ProcedureOrder order = procedureOrderRepository.findById(orderId)
            .orElseThrow(() -> procedureOrderNotFound(orderId));
        // A patient caller is bounded by ownership, not by a hospital, as on
        // the encounter, prescription and ultrasound reads: their own order
        // wherever it was written, another patient's exactly as a missing id,
        // and before the hospital check, which can answer differently. Staff
        // are held to their hospital — and, staff who are also patients
        // (#754's rule), read their own order elsewhere as its patient.
        if (subjectReadGuard.isPatientOnly(PatientSubjectReaderRoles.PROCEDURE_ORDER_READS)) {
            if (!subjectReadGuard.callerOwns(order.getPatient())) {
                throw procedureOrderNotFound(orderId);
            }
            return toResponseDTO(order);
        }
        if (!inScope(order) && !subjectReadGuard.ownsAsItsPatient(order.getPatient())) {
            throw procedureOrderNotFound(orderId);
        }
        return toResponseDTO(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcedureOrderResponseDTO> getProcedureOrdersForPatient(UUID patientId) {
        // A patient caller reads only their own. Another patient's id answers
        // exactly as an id that matches no row does -- an empty list -- and
        // before the hospital lookup below, which can answer differently.
        if (!subjectReadGuard.mayRead(PatientSubjectReaderRoles.PROCEDURE_ORDER_READS, patientId)) {
            return List.of();
        }
        if (subjectReadGuard.ownsAsItsPatient(patientId)) {
            // Their own record, read as its patient wherever it was written
            // (a patient, or staff who are also this patient, #754's rule).
            // Reading one's own record is not a disclosure: no reach recorded.
            return procedureOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId).stream()
                .map(this::toResponseDTO)
                .toList();
        }
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<ProcedureOrder> orders;
        if (activeHospitalId != null) {
            // E9 #59b — procedure orders follow the patient across the
            // readable hospitals (untagged, V158); every foreign row is accounted.
            UUID requesterUserId = roleValidator.getCurrentUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, activeHospitalId);
            orders = procedureOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, readable);
            reachRecorder.recordReach(patientId, activeHospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(orders.stream().map(o -> CrossHospitalReachRecorder.hospitalIdOf(o.getHospital())).toList(), activeHospitalId),
                "Cross-hospital procedure order read on the treatment relationship");
        } else {
            orders = procedureOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId);
        }
        return orders.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcedureOrderResponseDTO> getProcedureOrdersForHospital(UUID hospitalId, ProcedureOrderStatus status) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<ProcedureOrder> orders;
        if (status != null) {
            orders = procedureOrderRepository.findByHospital_IdAndStatusOrderByScheduledDatetimeAsc(effectiveHospitalId, status);
        } else {
            orders = procedureOrderRepository.findByHospital_IdOrderByOrderedAtDesc(effectiveHospitalId);
        }
        return orders.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcedureOrderResponseDTO> getProcedureOrdersOrderedBy(UUID providerId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<ProcedureOrder> orders;
        if (activeHospitalId != null) {
            orders = procedureOrderRepository.findByOrderingProvider_IdAndHospital_IdOrderByOrderedAtDesc(providerId, activeHospitalId);
        } else {
            orders = procedureOrderRepository.findByOrderingProvider_IdOrderByOrderedAtDesc(providerId);
        }
        return orders.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcedureOrderResponseDTO> getProcedureOrdersScheduledBetween(UUID hospitalId, LocalDateTime startDate, LocalDateTime endDate) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<ProcedureOrder> orders = procedureOrderRepository.findByHospital_IdAndScheduledDatetimeBetween(effectiveHospitalId, startDate, endDate);
        return orders.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    /**
     * QA D857: a reschedule to a past date was accepted (01/01/2020 made the
     * order "Planifié" and offered "Débuter la procédure"). Checked before
     * anything is applied, so a refused request changes nothing.
     */
    private void requireRescheduleNotInPast(ProcedureOrderUpdateDTO updateDTO, ProcedureOrder procedureOrder) {
        if (updateDTO.getScheduledDatetime() != null
                && !updateDTO.getScheduledDatetime().equals(procedureOrder.getScheduledDatetime())) {
            requireNotInPast(updateDTO.getScheduledDatetime());
        }
    }

    @Override
    public ProcedureOrderResponseDTO updateProcedureOrder(UUID orderId, ProcedureOrderUpdateDTO updateDTO) {
        ProcedureOrder procedureOrder = getProcedureOrderEntity(orderId);

        if (procedureOrder.getStatus() == ProcedureOrderStatus.COMPLETED) {
            throw new BusinessException("procedureOrder.completed.locked");
        }

        if (procedureOrder.getStatus() == ProcedureOrderStatus.CANCELLED) {
            throw new BusinessException("procedureOrder.cancelled.locked");
        }

        // Product decision 2026-10-28 (QA D930 class): recording the order's
        // execution - planning, pre-op steps, start, completion, postponement,
        // consent capture, site marking - stays with the care team (nurses,
        // the procedure and theatre staff), so care is never blocked. Ending
        // the order is the ordering clinician's decision: a CANCELLED sent
        // here passes the same author check as POST /cancel, before anything
        // is applied. A cancelled order is locked above for everyone, so
        // there is no way back out of CANCELLED to gate.
        boolean cancelling = updateDTO.getStatus() == ProcedureOrderStatus.CANCELLED;
        // QA D1098 (round 19): every cancel states why, on this path as on POST /cancel.
        if (cancelling) {
            requireCancelAuthor(procedureOrder);
        }
        String cancellationReason = cancelling
            ? com.example.hms.service.support.CancelledOrders.requireCancelReason(updateDTO.getCancellationReason())
            : null;

        requireRescheduleNotInPast(updateDTO, procedureOrder);

        if (updateDTO.getStatus() != null) {
            procedureOrder.setStatus(updateDTO.getStatus());
            if (updateDTO.getStatus() == ProcedureOrderStatus.COMPLETED) {
                procedureOrder.setCompletedAt(LocalDateTime.now(java.time.ZoneId.systemDefault()));
            }
            // The stamp belongs to the transition (the order was not cancelled,
            // checked above), as POST /cancel writes it; this path used to set
            // CANCELLED with no time and no reason.
            if (cancelling) {
                procedureOrder.setCancelledAt(LocalDateTime.now(java.time.ZoneId.systemDefault()));
                procedureOrder.setCancellationReason(cancellationReason);
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
        requireCancelAuthor(procedureOrder);

        if (procedureOrder.getStatus() == ProcedureOrderStatus.COMPLETED) {
            throw new BusinessException("procedureOrder.completed.cancel");
        }

        if (procedureOrder.getStatus() == ProcedureOrderStatus.CANCELLED) {
            throw new BusinessException("procedureOrder.alreadyCancelled");
        }
        // QA D1098: a blank reason passed the required request parameter.
        String reason = com.example.hms.service.support.CancelledOrders.requireCancelReason(cancellationReason);

        procedureOrder.setStatus(ProcedureOrderStatus.CANCELLED);
        procedureOrder.setCancelledAt(LocalDateTime.now());
        procedureOrder.setCancellationReason(reason);

        ProcedureOrder saved = procedureOrderRepository.save(procedureOrder);
        log.info("Procedure order {} cancelled: {}", orderId, cancellationReason);

        return toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcedureOrderResponseDTO> getPendingConsentOrders(UUID hospitalId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<ProcedureOrder> orders = procedureOrderRepository.findByHospital_IdAndStatusAndConsentObtainedFalse(effectiveHospitalId, ProcedureOrderStatus.SCHEDULED);
        return orders.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    /**
     * QA D857: how far before "now" a planned time may lie and still be taken
     * as now: the form's clock and the server's differ by a few minutes, and a
     * procedure planned "for now" is not a past one.
     */
    private static final long SCHEDULE_PAST_TOLERANCE_MINUTES = 15;

    /** QA D857: a planned date and time must not be in the past. */
    private static void requireNotInPast(LocalDateTime scheduledDatetime) {
        if (scheduledDatetime != null
                && scheduledDatetime.isBefore(LocalDateTime.now().minusMinutes(SCHEDULE_PAST_TOLERANCE_MINUTES))) {
            throw new BusinessException("procedureOrder.scheduledInPast");
        }
    }

    // Helper methods

    private ProcedureOrder getProcedureOrderEntity(UUID orderId) {
        ProcedureOrder order = procedureOrderRepository.findById(orderId)
            .orElseThrow(() -> procedureOrderNotFound(orderId));
        return requireInScope(order);
    }

    /** A procedure order outside the caller's active hospital answers as a missing one. */
    private ProcedureOrder requireInScope(ProcedureOrder order) {
        if (!inScope(order)) {
            throw procedureOrderNotFound(order.getId());
        }
        return order;
    }

    /** Is the order at the caller's active hospital (a null scope reaches every hospital)? */
    private boolean inScope(ProcedureOrder order) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        return activeHospitalId == null || order.getHospital() == null
            || activeHospitalId.equals(order.getHospital().getId());
    }

    /**
     * Product decision 2026-10-28 (QA D930 class): who may end a procedure
     * order. The clinician who ordered it; a physician (doctor, physician,
     * surgeon) of the order's hospital when the order was placed by a nurse or
     * midwife who drafts for a physician's signature there (the prescription
     * and imaging co-sign rule, QA D934); a verified super-admin in global
     * view. An order whose ordering staff record carries no user keeps the old
     * rule, anyone in scope, as imaging does for an order with no recorded
     * provider. Anyone else gets a translated 403; the order was resolved in
     * the caller's scope first, so the refusal discloses nothing.
     */
    private void requireCancelAuthor(ProcedureOrder order) {
        Staff orderer = order.getOrderingProvider();
        if (orderer == null || orderer.getUser() == null || orderer.getUser().getId() == null
                || com.example.hms.security.CallerIdentity.isGlobalSuperAdmin()
                || com.example.hms.security.CallerIdentity.isCaller(orderer)) {
            return;
        }
        UUID caller = com.example.hms.security.CallerIdentity.currentUserId();
        UUID hospitalId = com.example.hms.security.CallerIdentity.hospitalIdOf(order.getHospital());
        if (caller != null && hospitalId != null
                && roleValidator.draftsForPhysicianSignature(orderer.getUser().getId(), hospitalId)
                && roleValidator.isSigningPhysician(caller, hospitalId)) {
            return;
        }
        throw new com.example.hms.exception.ClientSafeAccessDeniedException(
            com.example.hms.utility.MessageUtil.resolveOrRaw("procedureOrder.cancelForbidden"));
    }

    private static ResourceNotFoundException procedureOrderNotFound(UUID orderId) {
        return new ResourceNotFoundException("procedureOrder.notFound", orderId);
    }

    private static UUID orderingUserId(ProcedureOrder order) {
        Staff orderer = order.getOrderingProvider();
        return orderer != null && orderer.getUser() != null ? orderer.getUser().getId() : null;
    }

    /**
     * QA D1081: the same question requireCancelAuthor asks, for the list's
     * per-row Annuler; unknown (null in the response) without an author or a
     * hospital.
     */
    private Optional<Boolean> authorIsPhysician(ProcedureOrder order) {
        UUID author = orderingUserId(order);
        UUID hospitalId = com.example.hms.security.CallerIdentity.hospitalIdOf(order.getHospital());
        if (author == null || hospitalId == null) {
            return Optional.empty();
        }
        return Optional.of(!roleValidator.draftsForPhysicianSignature(author, hospitalId));
    }

    private ProcedureOrderResponseDTO toResponseDTO(ProcedureOrder order) {
        UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
        String patientMrn = null;
        if (order.getPatient() != null && hospitalId != null) {
            patientMrn = order.getPatient().getMrnForHospital(hospitalId);
        }
        
        return ProcedureOrderResponseDTO.builder()
            .id(order.getId())
            .authorUserId(orderingUserId(order))
            .authorIsPhysician(authorIsPhysician(order).orElse(null))
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

    /**
     * The encounter an order or consultation is filed against must be this
     * patient's, at this hospital. The lookup is a bare {@code findById}, so
     * another hospital's (or another patient's) encounter used to be attached;
     * it now answers exactly as an encounter id that matches no row.
     */
    private static boolean belongsTo(Encounter encounter, Patient patient, Hospital hospital) {
        return encounter.getHospital() != null && encounter.getPatient() != null
            && encounter.getHospital().getId().equals(hospital.getId())
            && encounter.getPatient().getId().equals(patient.getId());
    }
}
