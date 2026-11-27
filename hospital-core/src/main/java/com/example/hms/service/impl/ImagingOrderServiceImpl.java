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
import com.example.hms.service.imaging.ImagingRoleScope;
import com.example.hms.service.imaging.ImagingRoleScope.Reader;
import com.example.hms.service.ImagingOrderService;
import com.example.hms.service.PatientSubjectReadGuard;
import com.example.hms.service.PatientSubjectReaderRoles;
import com.example.hms.service.support.PatientSafeView;
import com.example.hms.service.support.ImagingPreAuthorisationPolicy;
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
import com.example.hms.service.recordaccess.NeverSentRule;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class ImagingOrderServiceImpl implements ImagingOrderService {

    /** QA D1066: refused on any write to a cancelled order. */
    private static final String IMAGING_ORDER_CANCELLED_LOCKED = "imaging.order.cancelled.locked";
    /** QA D1098: an imaging order's resulted states, in lifecycle order. */
    private static final List<ImagingOrderStatus> RESULTED_STATES =
        List.of(ImagingOrderStatus.COMPLETED, ImagingOrderStatus.RESULTS_AVAILABLE);

    private static final int DEFAULT_DUPLICATE_LOOKBACK_DAYS = 30;
    /**
     * QA D1920: the longest window a duplicate check reads, whatever the
     * caller asks for. A product bound, not a clinical rule: a duplicate is a
     * recent order, and the check is not a way to page through history.
     */
    public static final int MAX_DUPLICATE_LOOKBACK_DAYS = 90;

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
        // QA D2915: an order is placed by a doctor, a nurse or a midwife OF this hospital.
        roles().requireOrdererAt(hospital.getId());
        requirePatientRegisteredAtActingHospital(request.getPatientId());

        com.example.hms.service.support.DeceasedPatientGuard.refuseNewCare(patient);
        requireNotInThePast(request.getScheduledDate(), null);
        // Provisional rule 2: « Commander quand même » names its reason.
        com.example.hms.service.imaging.ImagingDuplicateOverride.requireReason(request);
        ImagingOrder imagingOrder = imagingOrderMapper.toEntity(request, patient, hospital);
        // QA D3450 / D3254: the exam picked from the hospital's imaging catalogue names the order.
        applyCatalogueExam(imagingOrder, request.getCatalogueItemId(), hospital.getId());
        imagingOrder.setOrderedAt(LocalDateTime.now());
        // QA D972 (round 18): an order belongs to the visit it was written in,
        // which is how the checkout bills it.
        imagingOrder.setEncounterId(visitOf(request.getEncounterId(), patient.getId(), hospital.getId()));
        // QA D302: the ordering provider is whoever is signed in. A free-text
        // name in the request is a claim, never shown as if a user had placed
        // the order; without a resolved user the order carries no provider.
        com.example.hms.model.User orderingUser = resolveOrderingUser(orderingUserId);
        imagingOrder.setOrderingProviderUserId(orderingUser != null ? orderingUser.getId() : null);
        imagingOrder.setOrderingProviderName(orderingUser != null ? displayName(orderingUser) : null);
        // QA D4140: a request placed by a nurse or a midwife is not an order yet. It is kept pending, with who
        // asked, until a physician of this hospital authorises it (ImagingOrderAuthorisation, provisional).
        if (authorisation().stampRequest(imagingOrder, imagingOrder.getOrderingProviderUserId(),
                imagingOrder.getOrderingProviderName())) {
            imagingOrder.setStatus(ImagingOrderStatus.DRAFT);
        }

        // QA D3450 / D3254: the duplicate rule compares the order as it is stored: with a catalogue exam, the
        // exam's own modality and body region, not what the form displayed.
        // QA D3903: the server finds the duplicate itself, over at least the default window (a
        // client cannot narrow it away), and a duplicate is placed only with its reason, flag or no flag.
        List<ImagingOrder> duplicateMatches = loadDuplicateMatches(patient.getId(), hospital.getId(),
            imagingOrder.getModality(), imagingOrder.getBodyRegion(),
            enforcedLookback(request.getDuplicateLookbackDays()));
        com.example.hms.service.imaging.ImagingDuplicateOverride.requireReasonForDuplicate(request, duplicateMatches);
        if (!duplicateMatches.isEmpty()) {
            imagingOrder.setDuplicateOfRecentOrder(true);
            imagingOrder.setDuplicateReferenceOrderId(duplicateMatches.get(0).getId());
        } else {
            imagingOrder.setDuplicateOfRecentOrder(false);
            imagingOrder.setDuplicateReferenceOrderId(null);
        }
        com.example.hms.service.imaging.ImagingDuplicateOverride.apply(imagingOrder, duplicateMatches, request,
            imagingOrder.getOrderingProviderUserId(), imagingOrder.getOrderingProviderName(), LocalDateTime.now());
        ImagingPreAuthorisationPolicy.FollowUp preAuth = ImagingPreAuthorisationPolicy.reconcile(imagingOrder, LocalDateTime.now());

        ImagingOrder saved = imagingOrderRepository.save(imagingOrder);
        followUp(saved, preAuth);
        // QA D4140 review: the physicians are told a request waits for them (urgent for a STAT one).
        if (awaitingFollowUp != null && Boolean.TRUE.equals(saved.getPhysicianAuthorisationRequired())) {
            awaitingFollowUp.requestPlaced(saved);
        }
        if (duplicateOverride != null) {
            duplicateOverride.audit(saved, duplicateMatches);
        }
        return imagingOrderMapper.toResponseDTO(saved, duplicateMatches);
    }

    /** QA D3450 / D3254 (r70, V361): the hospital's imaging catalogue; null in a hand-built unit test. */
    private com.example.hms.service.imaging.ImagingCatalogueService imagingCatalogueService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setImagingCatalogueService(
            com.example.hms.service.imaging.ImagingCatalogueService imagingCatalogueService) {
        this.imagingCatalogueService = imagingCatalogueService;
    }

    /**
     * Writes the catalogue exam on the order (its item, code, name, modality), or none for a free-text order. An
     * exam that cannot be checked is never taken on trust: with no catalogue to ask, a named exam is refused.
     */
    private void applyCatalogueExam(ImagingOrder order, UUID catalogueItemId, UUID hospitalId) {
        if (imagingCatalogueService != null) {
            imagingCatalogueService.applyTo(order, catalogueItemId, hospitalId);
        } else if (catalogueItemId != null) {
            throw new com.example.hms.exception.FieldBusinessException("catalogueItemId", "imaging.catalogue.examUnknown");
        }
    }

    /**
     * QA D3450 / D3254: an edit that names no catalogue exam and leaves the study's name as it was keeps the exam
     * the order was picked from, as it was placed (an exam deactivated since then does not block the edit); a
     * newly named exam is checked, and a retyped name makes it a free-text order.
     */
    private void keepOrApplyCatalogueExamOnEdit(ImagingOrder order, UUID requestedItemId, UUID itemBefore,
                                                String studyBefore) {
        boolean examUnchanged = requestedItemId == null
            && keptCatalogueItem(itemBefore, studyBefore, order.getStudyType()) != null;
        if (examUnchanged) {
            return;
        }
        UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
        applyCatalogueExam(order, requestedItemId, hospitalId);
    }

    /** The exam an edited order keeps: the one it had, while its study is still named the same. */
    private static UUID keptCatalogueItem(UUID itemBefore, String studyBefore, String studyNow) {
        boolean sameName = studyBefore != null && studyNow != null
            && studyBefore.trim().equalsIgnoreCase(studyNow.trim());
        return sameName ? itemBefore : null;
    }

    /** QA D4140 review: the physicians' task for a request that waits for them; null in a hand-built unit test. */
    private com.example.hms.service.imaging.ImagingAuthorisationRequestFollowUp awaitingFollowUp;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAwaitingFollowUp(com.example.hms.service.imaging.ImagingAuthorisationRequestFollowUp awaitingFollowUp) {
        this.awaitingFollowUp = awaitingFollowUp;
    }

    private void settleAwaiting(ImagingOrder saved) {
        if (awaitingFollowUp != null) {
            awaitingFollowUp.requestSettled(saved);
        }
    }

    /** Provisional rule 1: the billing follow-up of a pending pre-authorisation; null in a hand-built unit test. */
    private com.example.hms.service.imaging.ImagingPreAuthorisationFollowUp preAuthorisationFollowUp;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPreAuthorisationFollowUp(
            com.example.hms.service.imaging.ImagingPreAuthorisationFollowUp preAuthorisationFollowUp) {
        this.preAuthorisationFollowUp = preAuthorisationFollowUp;
    }

    /** Provisional rule 2: the audit of a flagged placement; null in a hand-built unit test. */
    private com.example.hms.service.imaging.ImagingDuplicateOverride duplicateOverride;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDuplicateOverride(com.example.hms.service.imaging.ImagingDuplicateOverride duplicateOverride) {
        this.duplicateOverride = duplicateOverride;
    }

    /** Files or closes the pre-authorisation follow-up the policy's reconcile asked for. */
    private void followUp(ImagingOrder saved, ImagingPreAuthorisationPolicy.FollowUp followUp) {
        if (preAuthorisationFollowUp != null) {
            preAuthorisationFollowUp.apply(saved, followUp);
        }
    }

    /** QA D972: the visits an order can belong to; null in a hand-built unit test. */
    private com.example.hms.repository.EncounterRepository encounterRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEncounterRepository(com.example.hms.repository.EncounterRepository encounterRepository) {
        this.encounterRepository = encounterRepository;
    }

    /**
     * QA D972 (round 18): the visit an imaging order is written in. A named
     * visit must be this patient's, at this hospital: otherwise the order would
     * be billed on someone else's invoice, so it is answered as not found. With
     * none named (the imaging page has no visit field), the patient's open
     * visit here, the latest one, is the order's visit, as an order entered in
     * a real hospital's chart belongs to the current encounter; with no open
     * visit the order stands alone and is not billed at a checkout.
     */
    private UUID visitOf(UUID requested, UUID patientId, UUID hospitalId) {
        if (encounterRepository == null) {
            return requested;
        }
        if (requested != null) {
            return encounterRepository.findByIdAndHospital_Id(requested, hospitalId)
                .filter(enc -> enc.getPatient() != null && patientId.equals(enc.getPatient().getId()))
                .map(com.example.hms.model.Encounter::getId)
                .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound", requested));
        }
        return encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(patientId, hospitalId,
                com.example.hms.enums.EncounterStatus.CLOSED).stream()
            .filter(enc -> enc.getEncounterDate() != null)
            .max(java.util.Comparator.comparing(com.example.hms.model.Encounter::getEncounterDate))
            .map(com.example.hms.model.Encounter::getId)
            .orElse(null);
    }

    @Override
    public ImagingOrderResponseDTO updateOrder(UUID orderId, ImagingOrderRequestDTO request, UUID callerUserId) {
        ImagingOrder order = getOrderInScope(orderId);
        roles().requireOrderer(order);
        requireEditableBy(order, callerUserId);
        // QA D1066 class: a cancelled order is not edited back to life either.
        com.example.hms.service.support.CancelledOrders.requireNotCancelled(order.getStatus(),
            IMAGING_ORDER_CANCELLED_LOCKED);
        // QA D1428 class: a study done is what was done; a correction is a new order.
        if (RESULTED_STATES.contains(order.getStatus())) {
            throw new com.example.hms.exception.BusinessException("imaging.order.resulted.locked");
        }
        // QA D3903: the study as it stood before ANY part of this edit (patient and hospital included),
        // so an edit that moves the order onto another patient's or place's recent study is a change.
        String studyKeyBefore = duplicateKeyOf(order);

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
        UUID visitBefore = order.getEncounterId();
        ImagingOrderStatus statusBefore = order.getStatus();
        UUID catalogueItemBefore = order.getCatalogueItemId();
        String studyBefore = order.getStudyType();
        imagingOrderMapper.updateEntityFromRequest(order, request);
        keepOrApplyCatalogueExamOnEdit(order, request.getCatalogueItemId(), catalogueItemBefore, studyBefore);
        // QA D972: an edit keeps the order's visit (the form has no visit
        // field), and a visit it names must be this patient's at this hospital.
        if (request.getEncounterId() == null) {
            order.setEncounterId(visitBefore);
        } else if (order.getPatient() != null && order.getHospital() != null) {
            order.setEncounterId(visitOf(request.getEncounterId(), order.getPatient().getId(),
                order.getHospital().getId()));
        }

        List<ImagingOrder> duplicateMatches = reconcileDuplicateOnEdit(order, request, studyKeyBefore, callerUserId);
        // QA D4140: an edit never moves a request that waits for a physician: it keeps the status it had (a
        // pending draft stays a draft; an older order a nurse placed keeps its place in the workflow, whose
        // transitions an edit does not make).
        if (authorisation().awaitsPhysician(order)) {
            order.setStatus(statusBefore);
        }
        // Provisional rule 1: an edit that records the number clears the flag.
        ImagingPreAuthorisationPolicy.FollowUp preAuth = ImagingPreAuthorisationPolicy.reconcile(order, LocalDateTime.now());

        ImagingOrder saved = imagingOrderRepository.save(order);
        followUp(saved, preAuth);
        return imagingOrderMapper.toResponseDTO(saved, duplicateMatches);
    }

    @Override
    public ImagingOrderResponseDTO updateOrderStatus(UUID orderId, ImagingOrderStatusUpdateRequestDTO request) {
        ImagingOrder order = getOrderInScope(orderId);
        // QA D930 class: a cancel ends the order for the patient; it is the
        // ordering provider's or the imaging department's decision.
        // QA D1066 (round 18): a cancelled order is closed - not reopened, not
        // re-stamped. Undoing a cancellation is a new order, as in real hospitals.
        com.example.hms.service.support.CancelledOrders.requireNotCancelled(order.getStatus(),
            IMAGING_ORDER_CANCELLED_LOCKED);
        boolean cancelling = request.getStatus() == ImagingOrderStatus.CANCELLED;
        // QA D1098 (round 19): a resulted order is closed - it is neither
        // cancelled nor sent back to ORDERED - and every cancel states why.
        com.example.hms.service.support.CancelledOrders.requireResultStaysClosed(order.getStatus(),
            request.getStatus(), RESULTED_STATES);
        // QA D1428 class: nor does a same-status write rewrite its schedule and notes.
        if (RESULTED_STATES.contains(order.getStatus()) && request.getStatus() == order.getStatus()) {
            throw new com.example.hms.exception.FieldBusinessException("status", "imaging.order.resulted.locked");
        }
        // QA D1925 (r53): the steps follow each other; results come from a
        // signed report. A routine study needing pre-authorisation starts with
        // its number; an urgent or STAT one starts without it (provisional rule 1).
        com.example.hms.service.support.ImagingOrderTransitions.require(order.getStatus(), request.getStatus());
        // QA D4140 (provisional): a request placed by a nurse or a midwife is not scheduled or started before a
        // physician of the hospital has authorised it. It can only be kept pending, declined or withdrawn.
        authorisation().requireAuthorisedFor(order, request.getStatus());
        // Policy rule 3 (provisional): a technician starts, completes or discontinues the exam; the
        // order itself (its schedule, its pre-authorisation, calling it off before it starts) is not theirs.
        // QA D2915: what the caller is counts at the order's hospital (ImagingRoleScope), never
        // token-wide: who interprets there keeps every step, a technician there the exam's, anybody
        // else is refused, whatever role their token names.
        boolean technician = roles().actsAsTechnician(order);
        if (technician) {
            com.example.hms.service.support.ImagingExamRecord.requireTechnicianStep(order.getStatus(), request);
            // Owner decision 2026-11-20 (provisional): an ultrasound is a physician's, unless the hospital says otherwise.
            roles().requireMayPerform(order);
        }
        ImagingOrderStatus from = order.getStatus();
        ImagingPreAuthorisationPolicy.requireStartAllowed(request.getStatus(), order.getPriority(),
            request.getRequiresAuthorization() != null ? request.getRequiresAuthorization() : order.getRequiresAuthorization(),
            request.getAuthorizationNumber() != null ? request.getAuthorizationNumber() : order.getAuthorizationNumber());
        if (cancelling && !technician) {
            // A technician reaches here only to discontinue an exam in progress (requireTechnicianStep).
            requireCancelAuthor(order);
        }
        String cancellationReason = cancelling
            ? com.example.hms.service.support.CancelledOrders.requireCancelReason(request.getCancellationReason(), "cancellationReason")
            : null;

        requireNotInThePast(request.getScheduledDate(), order.getScheduledDate());
        order.setStatus(request.getStatus());
        // QA D1822 (r51): a status write carries the status and its reason. A
        // field it leaves out is kept: "start", "complete" and "cancel" erased
        // the schedule, the appointment location, the workflow notes and the
        // pre-authorisation. A field the caller does send is still applied.
        applyPresentSchedulingFields(order, request);
        // QA D503 class: who changed the status is the session, never the
        // performedBy fields of the request (kept on the DTO for old clients).
        UUID actorId = com.example.hms.security.CallerIdentity.currentUserId();
        com.example.hms.model.User actor = actorId == null ? null : userRepository.findById(actorId).orElse(null);
        order.setStatusUpdatedAt(LocalDateTime.now());
        order.setStatusUpdatedBy(actorId);
        // Policy rule 3: who performed the exam is whoever starts it, from the session. It fills the
        // report's "performed by", which no screen ever filled.
        com.example.hms.service.support.ImagingExamRecord.stampPerformer(order, from, request.getStatus(), actorId,
            actor != null ? displayName(actor) : null, staffAt(actorId, order.getHospital()),
            order.getStatusUpdatedAt());
        if (RESULTED_STATES.contains(request.getStatus())) {
            // r44: the order drawer's "Completed at" was never filled.
            order.markCompletedAt(order.getStatusUpdatedAt());
        }

        if (cancelling) {
            order.setCancellationReason(cancellationReason);
            order.setCancelledAt(LocalDateTime.now());
            order.setCancelledByUserId(actorId);
            order.setCancelledByName(actor != null ? displayName(actor) : null);
        }
        ImagingPreAuthorisationPolicy.FollowUp preAuth =
            ImagingPreAuthorisationPolicy.reconcile(order, order.getStatusUpdatedAt());

        ImagingOrder saved = imagingOrderRepository.save(order);
        followUp(saved, preAuth);
        if (saved.getStatus() == ImagingOrderStatus.CANCELLED) {
            settleAwaiting(saved);
        }
        return imagingOrderMapper.toResponseDTO(saved);
    }

    /**
     * QA D4140: whoever placed a request that still waits for a physician withdraws it herself, with a
     * reason. A nurse or a midwife holds no role on the status endpoint (she never starts, schedules or
     * cancels an order), so the withdrawal has its own door: her own pending request, nothing else.
     */
    @Override
    public ImagingOrderResponseDTO withdrawPendingRequest(UUID orderId, String reason) {
        ImagingOrder order = getOrderInScope(orderId);
        com.example.hms.service.support.CancelledOrders.requireNotCancelled(order.getStatus(),
            IMAGING_ORDER_CANCELLED_LOCKED);
        // A request that waits has no result; the shared rule says so for every cancellation (QA D1098).
        com.example.hms.service.support.CancelledOrders.requireResultStaysClosed(order.getStatus(),
            ImagingOrderStatus.CANCELLED, RESULTED_STATES);
        roles().requireOrderer(order);
        UUID actorId = com.example.hms.security.CallerIdentity.currentUserId();
        requireAuthor(order, actorId);
        String cancellationReason =
            com.example.hms.service.support.CancelledOrders.requireCancelReason(reason, "cancellationReason");
        com.example.hms.model.User actor = actorId == null ? null : userRepository.findById(actorId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(ImagingOrderStatus.CANCELLED);
        order.setStatusUpdatedAt(now);
        order.setStatusUpdatedBy(actorId);
        order.setCancellationReason(cancellationReason);
        order.setCancelledAt(now);
        order.setCancelledByUserId(actorId);
        order.setCancelledByName(actor != null ? displayName(actor) : null);
        ImagingPreAuthorisationPolicy.FollowUp preAuth = ImagingPreAuthorisationPolicy.reconcile(order, now);
        ImagingOrder saved = imagingOrderRepository.save(order);
        followUp(saved, preAuth);
        settleAwaiting(saved);
        return imagingOrderMapper.toResponseDTO(saved);
    }

    /** QA D4140: only whoever placed a request that still waits withdraws it (ImagingOrderAuthorisation#mayWithdraw). */
    private void requireAuthor(ImagingOrder order, UUID actorId) {
        if (!authorisation().mayWithdraw(order, actorId)) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("imaging.order.withdrawForbidden"));
        }
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
     * QA D930 class: who may cancel an imaging order. Its ordering provider, a
     * radiologist of the order's hospital (the department declines or calls
     * off an exam), or a verified super-admin in global view. An order with no
     * recorded provider (written before D302) keeps the old rule, as on edit
     * ({@link #requireEditableBy}). Any other doctor in scope gets 403. Nobody
     * reopens a cancelled order (QA D1066: a new order is placed instead).
     */
    private void requireCancelAuthor(ImagingOrder order) {
        UUID orderingProvider = order.getOrderingProviderUserId();
        if (orderingProvider == null || com.example.hms.security.CallerIdentity.isGlobalSuperAdmin()) {
            return;
        }
        UUID caller = com.example.hms.security.CallerIdentity.currentUserId();
        if (orderingProvider.equals(caller)) {
            return;
        }
        UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
        if (caller != null && hospitalId != null && roleValidator.hasRole(caller, hospitalId, "RADIOLOGIST")) {
            return;
        }
        // QA D4140: a physician of the hospital declines a request a nurse or a midwife placed.
        if (authorisation().mayDecline(order, caller)) {
            return;
        }
        throw new com.example.hms.exception.ClientSafeAccessDeniedException(
            com.example.hms.utility.MessageUtil.resolveOrRaw("imaging.order.cancelForbidden"));
    }

    /**
     * QA D930 class: who may sign an unsigned order. Its ordering provider; a
     * signing physician of the order's hospital when the provider is a nurse
     * or midwife who drafts for a physician's signature (as on prescriptions,
     * QA D934); a verified super-admin in global view; anyone in scope when no
     * provider is recorded (written before D302).
     */
    private void requireSignatureAuthor(ImagingOrder order, UUID signerUserId) {
        UUID orderingProvider = order.getOrderingProviderUserId();
        if (orderingProvider == null || orderingProvider.equals(signerUserId)
                || com.example.hms.security.CallerIdentity.isGlobalSuperAdmin()) {
            return;
        }
        UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
        if (hospitalId != null && roleValidator.draftsForPhysicianSignature(orderingProvider, hospitalId)
                && roleValidator.isSigningPhysician(signerUserId, hospitalId)) {
            return;
        }
        throw new com.example.hms.exception.ClientSafeAccessDeniedException(
            com.example.hms.utility.MessageUtil.resolveOrRaw("imaging.order.notOrderingProvider"));
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

    /** QA D1822: only the scheduling fields a status write actually carries; null keeps the stored value. */
    private static void applyPresentSchedulingFields(ImagingOrder order, ImagingOrderStatusUpdateRequestDTO request) {
        if (request.getScheduledDate() != null) {
            order.setScheduledDate(request.getScheduledDate());
        }
        if (request.getScheduledTime() != null) {
            order.setScheduledTime(request.getScheduledTime());
        }
        if (request.getAppointmentLocation() != null) {
            order.setAppointmentLocation(request.getAppointmentLocation());
        }
        if (request.getWorkflowNotes() != null) {
            order.setWorkflowNotes(request.getWorkflowNotes());
        }
        if (request.getRequiresAuthorization() != null) {
            order.setRequiresAuthorization(request.getRequiresAuthorization());
        }
        if (request.getAuthorizationNumber() != null) {
            order.setAuthorizationNumber(request.getAuthorizationNumber());
        }
    }

    static void requireNotInThePast(java.time.LocalDate requested, java.time.LocalDate current) {
        if (requested != null && !requested.equals(current) && requested.isBefore(java.time.LocalDate.now())) {
            throw new com.example.hms.exception.FieldBusinessException("scheduledDate", "imaging.scheduledDate.past");
        }
    }

    @Override
    public ImagingOrderResponseDTO captureProviderSignature(UUID orderId, ImagingOrderSignatureRequestDTO request,
                                                            UUID signerUserId) {
        ImagingOrder order = getOrderInScope(orderId);
        // QA D2915: an order is signed by a physician OF its hospital (a nurse there who is a doctor
        // elsewhere does not sign her own draft).
        roles().requireSigningPhysician(order);
        // QA D1066 class: a cancelled order is not signed into force.
        com.example.hms.service.support.CancelledOrders.requireNotCancelled(order.getStatus(),
            IMAGING_ORDER_CANCELLED_LOCKED);
        // QA D302: the signer and the signing time come from the session. A
        // body naming someone else is refused; a body-supplied time is ignored.
        com.example.hms.model.User signer = signerUserId == null ? null
            : userRepository.findById(signerUserId).orElse(null);
        if (signer == null) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException("imaging.signature.signerUnknown");
        }
        if (request.getProviderUserId() != null && !request.getProviderUserId().equals(signer.getId())) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException("imaging.signature.otherSigner");
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
        // QA D930 class: signing an unsigned order made the signer its ordering
        // provider, so any doctor in scope could take a colleague's draft. The
        // ordering provider signs; a nurse's or midwife's draft is signed by a
        // physician of the order's hospital (the prescription rule, QA D934).
        requireSignatureAuthor(order, signer.getId());
        order.setOrderingProviderName(displayName(signer));
        order.setOrderingProviderNpi(request.getProviderNpi());
        order.setOrderingProviderUserId(signer.getId());
        order.setProviderSignatureStatement(request.getSignatureStatement());
        order.setProviderSignedAt(LocalDateTime.now());
        order.setAttestationConfirmed(request.getAttestationConfirmed());
        if (order.getStatus() == ImagingOrderStatus.DRAFT) {
            order.setStatus(ImagingOrderStatus.ORDERED);
        }
        ImagingPreAuthorisationPolicy.FollowUp preAuth = ImagingPreAuthorisationPolicy.reconcile(order, LocalDateTime.now());

        ImagingOrder saved = imagingOrderRepository.save(order);
        followUp(saved, preAuth);
        // QA D4140 review: an authorised request no longer waits in the physicians' in-baskets.
        settleAwaiting(saved);
        return imagingOrderMapper.toResponseDTO(saved);
    }

    /**
     * Provisional rule 1 (2026-11-15): records the pre-authorisation number,
     * at any status the study reached: an urgent study is often done (and
     * reported) before the insurer answers. Recording it clears « Préautorisation
     * en attente » and closes the billing task. A cancelled order is closed
     * (QA D1066), unless the study was done before it was cancelled.
     */
    @Override
    public ImagingOrderResponseDTO recordAuthorization(UUID orderId,
            com.example.hms.payload.dto.imaging.ImagingOrderAuthorizationRequestDTO request) {
        ImagingOrder order = getOrderInScope(orderId);
        // QA D2915: the number is recorded by who interprets AT the order's hospital.
        roles().requireInterpreter(order);
        if (order.getAuthorizationPendingSince() == null) {
            com.example.hms.service.support.CancelledOrders.requireNotCancelled(order.getStatus(),
                IMAGING_ORDER_CANCELLED_LOCKED);
        }
        String number = request != null && request.getAuthorizationNumber() != null
            ? request.getAuthorizationNumber().trim() : "";
        if (number.isEmpty()) {
            throw new com.example.hms.exception.FieldBusinessException("authorizationNumber",
                "imaging.order.authorizationNumberBlank");
        }
        // Insurer claims PR 1 (D-15): the number reaches invoice lines and the paper; never a payment-card number.
        com.example.hms.utility.CardNumbers.requireNone(number, "authorizationNumber");
        ImagingOrder saved = applyAuthorization(order, number);
        // Insurer claims plan PR 1 (QA D3984): the number reaches the invoice lines the order was billed on.
        copyToInvoiceLines(saved, number, com.example.hms.security.CallerIdentity.currentUserId());
        return imagingOrderMapper.toResponseDTO(saved);
    }

    /** The order takes the number: pre-authorisation required and numbered, its pending flag and task reconciled. */
    private ImagingOrder applyAuthorization(ImagingOrder order, String number) {
        order.setRequiresAuthorization(true);
        order.setAuthorizationNumber(number);
        ImagingPreAuthorisationPolicy.FollowUp preAuth = ImagingPreAuthorisationPolicy.reconcile(order, LocalDateTime.now());
        ImagingOrder saved = imagingOrderRepository.save(order);
        followUp(saved, preAuth);
        return saved;
    }

    /** Insurer claims plan PR 1: copies the order's number onto its invoice lines; null in a hand-built unit test. */
    private com.example.hms.service.billing.InvoiceLinePreAuthorisations invoiceLinePreAuthorisations;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setInvoiceLinePreAuthorisations(
            com.example.hms.service.billing.InvoiceLinePreAuthorisations invoiceLinePreAuthorisations) {
        this.invoiceLinePreAuthorisations = invoiceLinePreAuthorisations;
    }

    private int copyToInvoiceLines(ImagingOrder order, String number, UUID actorId) {
        if (invoiceLinePreAuthorisations == null || order.getHospital() == null) {
            return 0;
        }
        return invoiceLinePreAuthorisations.copyFromOrder(
            com.example.hms.service.billing.BillableOrderType.IMAGING_ORDER, order.getId(),
            order.getHospital().getId(), number, actorId);
    }

    /**
     * Insurer claims plan PR 1 (QA D3984, US-1): the desk or billing records the number the insurer gave, without
     * any clinical field in the answer (owner rule 2026-11-06: reception sees no clinical notes). Who: the
     * pre-authorisation recorders ({@code RoleValidator#canRecordPreAuthorisation}) at the order's hospital, which
     * must be the acting one; never a support session. A cancelled order is closed (QA D1066) unless its study was
     * done before it was cancelled, as on the clinicians' path.
     */
    @Override
    public com.example.hms.payload.dto.imaging.ImagingPreAuthorisationDeskDTO recordAuthorizationAtDesk(UUID orderId,
                                                                                                      String rawNumber) {
        com.example.hms.security.context.ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService,
            "pre-authorisation number");
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        UUID userId = roleValidator.getCurrentUserId();
        ImagingOrder order = getOrderEntity(orderId);
        if (hospitalId == null || order.getHospital() == null || !hospitalId.equals(order.getHospital().getId())) {
            throw new ResourceNotFoundException("imaging.order.notFound", orderId);
        }
        if (userId == null || !roleValidator.canRecordPreAuthorisation(userId, hospitalId)) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolve("billing.preAuthorisation.recordersOnly"));
        }
        if (order.getAuthorizationPendingSince() == null) {
            com.example.hms.service.support.CancelledOrders.requireNotCancelled(order.getStatus(),
                IMAGING_ORDER_CANCELLED_LOCKED);
        }
        String number = com.example.hms.service.billing.InvoiceLinePreAuthorisations.normalise(rawNumber,
            com.example.hms.service.billing.InvoiceLinePreAuthorisations.FIELD);
        ImagingOrder saved = applyAuthorization(order, number);
        int lines = copyToInvoiceLines(saved, number, userId);
        return new com.example.hms.payload.dto.imaging.ImagingPreAuthorisationDeskDTO(saved.getId(),
            saved.getAuthorizationNumber(), ImagingPreAuthorisationPolicy.isPending(saved), lines);
    }

    @Override
    public boolean recordAuthorizationFromInvoiceLine(UUID orderId, UUID hospitalId, String number) {
        if (orderId == null || hospitalId == null || number == null || number.isBlank()) {
            return false;
        }
        ImagingOrder order = imagingOrderRepository.findById(orderId)
            .filter(found -> found.getHospital() != null && hospitalId.equals(found.getHospital().getId()))
            .orElse(null);
        if (order == null || (order.getStatus() == ImagingOrderStatus.CANCELLED
                && order.getAuthorizationPendingSince() == null)) {
            return false;
        }
        applyAuthorization(order, number);
        return true;
    }

    /** The actor's staff row at the order's hospital, or null (a clinician with none there). */
    private com.example.hms.model.Staff staffAt(UUID userId, Hospital hospital) {
        if (userId == null || hospital == null || hospital.getId() == null) {
            return null;
        }
        return staffRepository.findByUserIdAndHospitalId(userId, hospital.getId()).orElse(null);
    }

    /**
     * Policy rule 3 (provisional): the patient's arrival at the imaging desk. Recorded once, from
     * the session; a repeat changes nothing. Open while the order is placed and not yet done.
     */
    @Override
    public ImagingOrderResponseDTO checkInPatient(UUID orderId) {
        ImagingOrder order = getOrderInScope(orderId);
        com.example.hms.service.support.CancelledOrders.requireNotCancelled(order.getStatus(),
            IMAGING_ORDER_CANCELLED_LOCKED);
        com.example.hms.service.support.ImagingExamRecord.requireCheckInOpen(order.getStatus());
        // QA D4140: no patient is checked in on a request no physician has authorised.
        authorisation().requireAuthorised(order);
        // Whoever holds no exam role at the order's hospital is refused here; the answer itself changes nothing.
        roles().actsAsTechnician(order);
        if (order.getCheckedInAt() != null) {
            return imagingOrderMapper.toResponseDTO(order);
        }
        UUID actorId = com.example.hms.security.CallerIdentity.currentUserId();
        com.example.hms.model.User actor = actorId == null ? null : userRepository.findById(actorId).orElse(null);
        order.setCheckedInAt(LocalDateTime.now(java.time.ZoneId.systemDefault()));
        order.setCheckedInByUserId(actorId);
        order.setCheckedInByName(actor != null ? displayName(actor) : null);
        return imagingOrderMapper.toResponseDTO(imagingOrderRepository.save(order));
    }

    /**
     * Policy rule 3 (provisional): how the exam went. The record is replaced as sent: every field
     * is optional and one left out is cleared (the portal refuses a save with nothing in it). Who
     * wrote it and when come from the session. Open from the start of the exam until a report is
     * signed.
     */
    @Override
    public ImagingOrderResponseDTO recordExam(UUID orderId,
                                              com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO request) {
        ImagingOrder order = getOrderInScope(orderId);
        com.example.hms.service.support.CancelledOrders.requireNotCancelled(order.getStatus(),
            IMAGING_ORDER_CANCELLED_LOCKED);
        com.example.hms.service.support.ImagingExamRecord.requireRecordOpen(order.getStatus());
        // QA D4140: nor is an exam recorded on one.
        authorisation().requireAuthorised(order);
        if (roles().actsAsTechnician(order)) {
            roles().requireMayPerform(order);
        }
        java.util.Map<String, String> changes = com.example.hms.service.support.ImagingExamRecord.changes(order, request);
        order.setExamTechnicalNote(blankToNull(request.getTechnicalNote()));
        order.setExamContrastAdministered(request.getContrastAdministered());
        order.setExamContrastDetails(blankToNull(request.getContrastDetails()));
        order.setExamRadiationDoseMgy(request.getRadiationDoseMgy());
        order.setExamRecordedAt(LocalDateTime.now(java.time.ZoneId.systemDefault()));
        order.setExamRecordedByUserId(com.example.hms.security.CallerIdentity.currentUserId());
        ImagingOrder saved = imagingOrderRepository.save(order);
        auditExamRecord(saved, changes);
        return imagingOrderMapper.toResponseDTO(saved);
    }

    /** QA D2915: what the caller is at the order's hospital; never the role their token names. */
    private ImagingRoleScope roles() {
        return new ImagingRoleScope(roleValidator);
    }

    /** QA D4140: whether a request waits for a physician, and who may authorise, decline or withdraw it. */
    private com.example.hms.service.imaging.ImagingOrderAuthorisation authorisation() {
        return new com.example.hms.service.imaging.ImagingOrderAuthorisation(roleValidator);
    }

    /** The exam record's own audit row; setter-injected so the unit tests that build this service keep working. */
    private com.example.hms.service.AuditEventLogService auditEventLogService;

    @Autowired(required = false)
    public void setAuditEventLogService(com.example.hms.service.AuditEventLogService auditEventLogService) {
        this.auditEventLogService = auditEventLogService;
    }

    /**
     * What an exam record write changed, after the commit and best effort. The two structured
     * values are audited before and after; the two free texts (encrypted at rest, they can name the
     * patient) only as set, changed or cleared, never their content.
     */
    private void auditExamRecord(ImagingOrder order, java.util.Map<String, String> changes) {
        if (auditEventLogService == null || changes.isEmpty()) {
            return;
        }
        UUID orderId = order.getId();
        UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
        UUID patientId = order.getPatient() != null ? order.getPatient().getId() : null;
        UUID actorId = com.example.hms.security.CallerIdentity.currentUserId();
        java.util.Map<String, String> details = new java.util.LinkedHashMap<>(changes);
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                    .eventType(com.example.hms.enums.AuditEventType.DATA_UPDATE)
                    .status(com.example.hms.enums.AuditStatus.SUCCESS)
                    .userId(actorId)
                    .hospitalId(hospitalId)
                    .patientId(patientId)
                    .entityType("IMAGING_ORDER")
                    .resourceId(orderId == null ? null : orderId.toString())
                    .eventDescription("Imaging exam record updated")
                    .details(details)
                    .build());
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(ImagingOrderServiceImpl.class)
                    .warn("Exam record audit failed for imaging order {}: {}", orderId, ex.getClass().getSimpleName());
            }
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
            // QA D2915: a reader of THIS hospital (care team, technician, administrator), or refused.
            roles().reader(order);
            return order;
        }
        // User decision 2026-11-14: an administrator reads the hospitals they act at, never
        // across on a patient's treatment relationship (that is the care team's).
        // Policy rule 3: nor does an imaging technician, who works the worklist of their own hospital.
        // QA D2915: the care team is the one of the hospital the request acts at.
        if (roles().readerAt(activeHospitalId) != Reader.CLINICAL) {
            throw new ResourceNotFoundException("imaging.order.notFound", orderId);
        }
        UUID patientId = order.getPatient() != null ? order.getPatient().getId() : null;
        UUID requesterUserId = roleValidator.getCurrentUserId();
        // QA D652: another hospital's never-sent DRAFT is its work in progress (NeverSentRule).
        if (patientId == null
                || !NeverSentRule.readableHere(orderHospitalId, activeHospitalId, wasSent(order))
                || !recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, activeHospitalId)
                    .contains(orderHospitalId)) {
            throw new ResourceNotFoundException("imaging.order.notFound", orderId);
        }
        reachRecorder.recordReach(patientId, activeHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(List.of(orderHospitalId), activeHospitalId),
            "Cross-hospital imaging order read on the treatment relationship");
        return order;
    }

    private static boolean wasSent(ImagingOrder order) {
        return NeverSentRule.sent(order.getStatus());
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
            // QA D1960: the patient's copy, not the care team's working notes.
            return own.stream().map(imagingOrderMapper::toResponseDTO).map(PatientSafeView::imagingOrder).toList();
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
            // QA D2915: the chart list is the care team's of the acting hospital; a technician there
            // (whatever their token names) has none.
            Reader reader = roles().readerAt(activeHospitalId);
            if (reader == Reader.TECHNICIAN) {
                roles().requireClinicalReaderAt(activeHospitalId);
            }
            // User decision 2026-11-14: an administrator reads the acting hospital only.
            Set<UUID> readable = reader.administrator()
                ? Set.of(activeHospitalId)
                : recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, activeHospitalId);
            orders = status != null
                ? imagingOrderRepository.findByPatient_IdAndHospital_IdInAndStatusOrderByOrderedAtDesc(patientId, readable, status)
                : imagingOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, readable);
            // QA D652: less another hospital's never-sent DRAFTs, as the by-id read (NeverSentRule).
            orders = NeverSentRule.imagingOrdersReadableHere(orders, activeHospitalId);
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
        // The path names the hospital: the interceptor has narrowed the scope to it, or refused.
        // Belt and braces for a caller with no tenant context: never another hospital than the acting one.
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && !activeHospitalId.equals(hospitalId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Imaging orders can only be listed for your active hospital.");
        }
        // QA D2915: the worklist is read by the care team, a technician or an administrator of THIS hospital.
        roles().readerAt(hospitalId);
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
    public List<ImagingOrderDuplicateMatchDTO> previewDuplicates(UUID patientId, UUID hospitalId, ImagingModality modality, String bodyRegion, Integer lookbackDays) {
        if (patientId == null || modality == null) {
            return Collections.emptyList();
        }
        // QA D1920: the orders the new one would duplicate are those at the
        // hospital it would be placed at -- the caller's own when pinned (a
        // different hospital named is answered as a missing one), the named
        // one for a super-admin in global view, who names none: no list.
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID targetHospitalId;
        if (activeHospitalId != null) {
            requireActingHospital(hospitalId != null ? hospitalId : activeHospitalId);
            targetHospitalId = activeHospitalId;
        } else if (hospitalId != null) {
            targetHospitalId = hospitalId;
        } else {
            return Collections.emptyList();
        }
        // QA D2915: the preview belongs to ordering: a doctor, a nurse or a midwife of that hospital.
        roles().requireOrdererAt(targetHospitalId);
        // ...and only for a patient registered there, answered as unknown
        // otherwise, as createOrder refuses to place the order.
        if (!registrationRepository.existsByPatientIdAndHospitalId(patientId, targetHospitalId)) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }
        List<ImagingOrder> matches = loadDuplicateMatches(patientId, targetHospitalId, modality, bodyRegion, lookbackDays);
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

    /**
     * This patient's possible duplicates at {@code hospitalId} only (QA D1920),
     * inside the requested window clamped to {@link #MAX_DUPLICATE_LOOKBACK_DAYS}.
     * No hospital, no matches: an order without one duplicates nothing.
     */
    private List<ImagingOrder> loadDuplicateMatches(UUID patientId, UUID hospitalId, ImagingModality modality, String bodyRegion, Integer lookbackDays) {
        if (patientId == null || hospitalId == null || modality == null) {
            return Collections.emptyList();
        }
        LocalDateTime orderedAfter = LocalDateTime.now().minusDays(duplicateWindowDays(lookbackDays));
        String normalizedBodyRegion = normalizeBodyRegion(bodyRegion);
        return imagingOrderRepository.findPotentialDuplicates(patientId, hospitalId, modality, normalizedBodyRegion, orderedAfter);
    }

    /**
     * The edited order's duplicates (QA D970 sibling: never itself) and its duplicate flag. QA D3903
     * (same class): an edit that turns the order into another study (patient, place, modality or
     * region) is a new ordering decision; if that study was ordered recently, the edit needs the
     * reason a new order would, stored with who and when. An edit that leaves the study as it was
     * decides nothing new.
     */
    private List<ImagingOrder> reconcileDuplicateOnEdit(ImagingOrder order, ImagingOrderRequestDTO request,
                                                       String studyKeyBefore, UUID callerUserId) {
        List<ImagingOrder> duplicateMatches = loadDuplicateMatches(
            order.getPatient() != null ? order.getPatient().getId() : null,
            order.getHospital() != null ? order.getHospital().getId() : null,
            order.getModality(),
            order.getBodyRegion(),
            enforcedLookback(request.getDuplicateLookbackDays())
        ).stream().filter(match -> order.getId() == null || !order.getId().equals(match.getId())).toList();
        boolean studyChanged = !java.util.Objects.equals(studyKeyBefore, duplicateKeyOf(order));
        if (studyChanged) {
            com.example.hms.service.imaging.ImagingDuplicateOverride.requireReasonForDuplicate(request, duplicateMatches);
        }
        order.setDuplicateOfRecentOrder(!duplicateMatches.isEmpty());
        order.setDuplicateReferenceOrderId(duplicateMatches.isEmpty() ? null : duplicateMatches.get(0).getId());
        if (studyChanged) {
            com.example.hms.model.User editor = resolveOrderingUser(callerUserId);
            com.example.hms.service.imaging.ImagingDuplicateOverride.apply(order, duplicateMatches, request,
                editor != null ? editor.getId() : null, editor != null ? displayName(editor) : null,
                LocalDateTime.now());
        }
        return duplicateMatches;
    }

    /**
     * QA D3903: the window a placed or edited order is checked over: what the caller asked when it
     * is wider than the default, never narrower (a client must not shrink the check away).
     */
    private static Integer enforcedLookback(Integer lookbackDays) {
        return lookbackDays == null || lookbackDays < DEFAULT_DUPLICATE_LOOKBACK_DAYS ? null : lookbackDays;
    }

    /** What a duplicate is judged on (patient, hospital, modality, region), as one comparable key. */
    private String duplicateKeyOf(ImagingOrder order) {
        return (order.getPatient() != null ? order.getPatient().getId() : null) + "|"
            + (order.getHospital() != null ? order.getHospital().getId() : null) + "|"
            + order.getModality() + "|" + java.util.Optional.ofNullable(normalizeBodyRegion(order.getBodyRegion()))
                .map(region -> region.toLowerCase(java.util.Locale.ROOT)).orElse(null);
    }

    /** The window in days: the default when none or a non-positive one is asked, never above the cap. */
    public static int duplicateWindowDays(Integer lookbackDays) {
        if (lookbackDays == null || lookbackDays <= 0) {
            return DEFAULT_DUPLICATE_LOOKBACK_DAYS;
        }
        return Math.min(lookbackDays, MAX_DUPLICATE_LOOKBACK_DAYS);
    }

    private String normalizeBodyRegion(String bodyRegion) {
        if (bodyRegion == null) {
            return null;
        }
        String trimmed = bodyRegion.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
