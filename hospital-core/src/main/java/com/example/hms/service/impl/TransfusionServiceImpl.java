package com.example.hms.service.impl;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.EmergencyReleaseType;
import com.example.hms.enums.ChildbearingPotential;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionAdministrationStatus;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.EmergencyBloodRelease;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionAdministration;
import com.example.hms.model.TransfusionCrossmatch;
import com.example.hms.model.TransfusionReaction;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.payload.dto.transfusion.BloodUnitRequestDTO;
import com.example.hms.payload.dto.transfusion.BloodUnitResponseDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseOptionsDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionReactionRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionReactionResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestResponseDTO;
import com.example.hms.repository.BloodUnitRepository;
import com.example.hms.repository.EmergencyBloodReleaseRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientBloodGroupRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TransfusionAdministrationRepository;
import com.example.hms.repository.TransfusionCrossmatchRepository;
import com.example.hms.repository.TransfusionReactionRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.TransfusionService;
import com.example.hms.service.support.EmergencyReleasePolicy;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.DisplayDates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The transfusion loop (Tier 2 item 28).
 *
 * <p>Every safety rule that matters lives here rather than in the UI, and each
 * one fails closed. See {@link com.example.hms.service.TransfusionService} for
 * the list; the reasoning for each is at its call site.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TransfusionServiceImpl implements TransfusionService {
    /** Request properties a refusal names (QA D1352 class, round 30). */
    private static final String FIELD_BLOOD_UNIT_ID = "bloodUnitId";
    private static final String FIELD_REASON = "reason";
    /** The stop_reason and discard_reason columns. */
    private static final int MAX_REASON_LENGTH = 500;


    private static final String MSG_REQUEST_NOT_FOUND = "transfusion.request.notFound";
    private static final String MSG_UNIT_NOT_FOUND = "transfusion.unit.notFound";
    private static final String MSG_GROUP_NOT_FOUND = "transfusion.bloodGroup.notFound";
    private static final String MSG_ADMIN_NOT_FOUND = "transfusion.administration.notFound";

    /** The in-basket item of an incompatible result on a bag already out of the lab. */
    public static final String INCOMPATIBLE_TITLE_KEY = "transfusion.crossmatch.incompatible.inBasketTitle";
    public static final String INCOMPATIBLE_RUNNING_BODY_KEY = "transfusion.crossmatch.incompatible.running.inBasketBody";
    public static final String INCOMPATIBLE_ISSUED_BODY_KEY = "transfusion.crossmatch.incompatible.issued.inBasketBody";
    /** The item's reference names the result, not the row: see fileIncompatibleResultAfterCommit. */
    static final String CROSSMATCH_REFERENCE_TYPE = "TRANSFUSION_CROSSMATCH";
    private static final String REQUEST_ENTITY_TYPE = "TRANSFUSION_REQUEST";
    public static final String INCOMPATIBLE_FINISHED_BODY_KEY = "transfusion.crossmatch.incompatible.finished.inBasketBody";
    /** The lab director's review item for every serologically incompatible release. */
    public static final String RELEASE_REVIEW_TITLE_KEY = "transfusion.emergency.incompatible.inBasketTitle";
    public static final String RELEASE_REVIEW_BODY_KEY = "transfusion.emergency.incompatible.inBasketBody";
    static final String RELEASE_REFERENCE_TYPE = "TRANSFUSION_EMERGENCY_RELEASE";
    private static final String FIELD_RELEASE_TYPE = "releaseType";
    private static final String FIELD_AUTHORISER = "authoriserUserId";
    private static final String FIELD_COMPATIBLE = "compatible";
    private static final String FIELD_NEGATIVE_UNOBTAINABLE = "negativeUnobtainable";
    private static final Set<String> LAB_DIRECTOR_ROLE_CODES = Set.of(SecurityConstants.ROLE_LAB_DIRECTOR);

    /** Default life of an antibody screen and of a crossmatch reservation. */
    private static final int DEFAULT_VALIDITY_HOURS = 72;

    /** QA D883: the most a single blood bag holds (an apheresis plasma bag is about 800 mL). */
    public static final int MAX_UNIT_VOLUME_ML = 1000;

    /** QA D858: tolerated difference between the ward PC's clock and the server's. */
    private static final java.time.Duration CLOCK_SKEW = java.time.Duration.ofMinutes(5);

    private final PatientBloodGroupRepository bloodGroupRepository;
    private final PatientRepository patientRepository;
    private final TransfusionRequestRepository requestRepository;
    private final BloodUnitRepository unitRepository;
    private final TransfusionCrossmatchRepository crossmatchRepository;
    private final TransfusionAdministrationRepository administrationRepository;
    private final TransfusionReactionRepository reactionRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final PatientChartAccess patientChartAccess;
    private final TransfusionMapper mapper;
    private final RoleValidator roleValidator;
    private final com.example.hms.service.InBasketService inBasketService;
    private final com.example.hms.service.AuditEventLogService auditEventLogService;
    private final EmergencyBloodReleaseRepository releaseRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    /**
     * Injected rather than reading the system clock inline, following
     * ReferralExpiryServiceImpl and PrenatalSchedulingServiceImpl. Expiry is
     * load-bearing here — a crossmatch reservation lapses, a screen goes
     * stale, a unit dates — so "now" has to be something a test can move.
     */
    private final Clock clock;

    // ── Type and screen ─────────────────────────────────────────────────

    @Override
    public PatientBloodGroupResponseDTO recordBloodGroup(PatientBloodGroupRequestDTO request) {
        UUID hospitalId = requireHospital();
        // QA D858 class: a specimen cannot have been collected in the future,
        // and a screen that is already stale when recorded protects nobody.
        requireNotInFuture(request.getSpecimenCollectedAt(), "specimenCollectedAt",
            "transfusion.bloodGroup.specimenInFuture");
        requireInFuture(request.getExpiresAt());
        Patient patient = patientChartAccess.require(request.getPatientId(), hospitalId);

        // QA CX-011: two repeats at once take turns on the patient row, so the
        // second supersedes what the first wrote instead of colliding with it.
        // The current row cannot be the lock: a waiter would find it superseded
        // and insert beside the new one, and a first screen has no row at all.
        patientRepository.lockByIdUnscoped(patient.getId());

        bloodGroupRepository
            .findByPatient_IdAndHospital_IdAndSupersededFalse(patient.getId(), hospitalId)
            .ifPresent(current -> {
                // A blood group does not change. A disagreement means one of the
                // two results is wrong, and quietly letting the newer one win is
                // how the wrong blood reaches a patient — so it must be declared.
                boolean groupChanged = current.getAboGroup() != request.getAboGroup()
                    || current.getRhFactor() != request.getRhFactor();
                if (groupChanged && !StringUtils.hasText(request.getCorrectionReason())) {
                    throw new com.example.hms.exception.FieldBusinessException("correctionReason", "transfusion.bloodGroup.changeNeedsReason",
                        groupLabel(current.getAboGroup(), current.getRhFactor()),
                        groupLabel(request.getAboGroup(), request.getRhFactor()));
                }
                current.setSuperseded(Boolean.TRUE);
                // QA CX-011: flushed now. Hibernate runs INSERTs before UPDATEs, so
                // with a plain save the new row reached uq_blood_group_current while
                // this one still held it, and every repeat screen was refused as a
                // duplicate.
                bloodGroupRepository.saveAndFlush(current);
            });

        LocalDateTime now = LocalDateTime.now(clock);
        PatientBloodGroup group = PatientBloodGroup.builder()
            .patient(patient)
            .hospital(hospitalRef(hospitalId))
            .aboGroup(request.getAboGroup())
            .rhFactor(request.getRhFactor())
            .antibodyScreen(request.getAntibodyScreen())
            .antibodyDetail(request.getAntibodyDetail())
            .specimenCollectedAt(request.getSpecimenCollectedAt())
            .performedAt(now)
            .expiresAt(request.getExpiresAt() != null
                ? request.getExpiresAt() : now.plusHours(DEFAULT_VALIDITY_HOURS))
            .performedBy(currentStaff(hospitalId))
            .superseded(Boolean.FALSE)
            .notes(request.getNotes())
            .correctionReason(StringUtils.hasText(request.getCorrectionReason())
                ? request.getCorrectionReason().trim() : null)
            .build();

        PatientBloodGroup saved = bloodGroupRepository.save(group);
        moveOpenRequestsOnto(saved);
        return mapper.toDto(saved);
    }

    /**
     * QA CX-011: a request still being worked follows the patient's current
     * screen; one that is closed keeps the screen it was decided on, as history.
     */
    private void moveOpenRequestsOnto(PatientBloodGroup screen) {
        // Saving a request writes the whole row: each is read under its lock, so a cancel
        // committed meanwhile is seen and not written over.
        requestRepository.findOpenIds(screen.getPatient().getId(), screen.getHospital().getId())
            .forEach(requestRepository::lockById);
        requestRepository
            .findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(screen.getPatient().getId(), screen.getHospital().getId())
            .stream()
            .filter(open -> !open.isTerminal())
            .forEach(open -> {
                open.setBloodGroup(screen);
                requestRepository.save(open);
            });
    }

    /**
     * The screen a decision about this request stands on: the patient's current
     * one, never the one the request happened to be raised against. An emergency
     * request raised untyped and typed afterwards lands here too.
     */
    private PatientBloodGroup currentScreen(TransfusionRequest request) {
        if (request.getPatient() == null || request.getHospital() == null) {
            return request.getBloodGroup();
        }
        PatientBloodGroup current = bloodGroupRepository
            .findByPatient_IdAndHospital_IdAndSupersededFalse(request.getPatient().getId(), request.getHospital().getId())
            .orElse(request.getBloodGroup());
        if (current != request.getBloodGroup()) {
            request.setBloodGroup(current);
            requestRepository.save(request);
        }
        return current;
    }

    @Override
    @Transactional(readOnly = true)
    public PatientBloodGroupResponseDTO getCurrentBloodGroup(UUID patientId) {
        UUID hospitalId = requireHospital();
        patientChartAccess.require(patientId, hospitalId);
        return bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId)
            .map(mapper::toDto)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_GROUP_NOT_FOUND, patientId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientBloodGroupResponseDTO> getBloodGroupHistory(UUID patientId) {
        UUID hospitalId = requireHospital();
        patientChartAccess.require(patientId, hospitalId);
        return bloodGroupRepository
            .findByPatient_IdAndHospital_IdOrderByPerformedAtDesc(patientId, hospitalId)
            .stream().map(mapper::toDto).toList();
    }

    // ── Units ───────────────────────────────────────────────────────────

    @Override
    public BloodUnitResponseDTO receiveUnit(BloodUnitRequestDTO request) {
        UUID hospitalId = requireHospital();

        unitRepository.findByHospital_IdAndUnitNumber(hospitalId, request.getUnitNumber())
            .ifPresent(existing -> {
                throw new com.example.hms.exception.FieldBusinessException("unitNumber", "transfusion.unit.duplicate", request.getUnitNumber());
            });
        LocalDate today = LocalDate.now(clock);
        if (request.getExpiresOn() != null && !request.getExpiresOn().isAfter(today)) {
            throw new com.example.hms.exception.FieldBusinessException("expiresOn", "transfusion.unit.expiredOnReceipt", request.getUnitNumber());
        }
        // QA D883: a bag collected in 2027 was received into stock. A donation
        // date after today, or after the bag's own expiry, is a typing error.
        if (request.getCollectedOn() != null && request.getCollectedOn().isAfter(today)) {
            throw new com.example.hms.exception.FieldBusinessException("collectedOn", "transfusion.unit.collectedInFuture");
        }
        if (request.getCollectedOn() != null && request.getExpiresOn() != null
                && request.getCollectedOn().isAfter(request.getExpiresOn())) {
            throw new com.example.hms.exception.FieldBusinessException("expiresOn", "transfusion.unit.collectedAfterExpiry");
        }

        BloodUnit unit = BloodUnit.builder()
            .hospital(hospitalRef(hospitalId))
            .request(request.getRequestId() != null ? loadRequestScoped(request.getRequestId()) : null)
            .unitNumber(request.getUnitNumber())
            .productType(request.getProductType())
            .aboGroup(request.getAboGroup())
            .rhFactor(request.getRhFactor())
            .volumeMl(request.getVolumeMl())
            .collectedOn(request.getCollectedOn())
            .expiresOn(request.getExpiresOn())
            .source(request.getSource())
            .status(BloodUnitStatus.AVAILABLE)
            .notes(request.getNotes())
            .build();

        return mapper.toDto(unitRepository.save(unit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloodUnitResponseDTO> listUnits(String status) {
        UUID hospitalId = requireHospital();
        List<BloodUnit> units = StringUtils.hasText(status)
            ? unitRepository.findByHospital_IdAndStatusOrderByExpiresOnAsc(hospitalId, parseUnitStatus(status))
            : unitRepository.findByHospital_IdOrderByExpiresOnAsc(hospitalId);
        return units.stream().map(mapper::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloodUnitResponseDTO> listAssignableUnits() {
        return unitRepository.findAssignable(requireHospital(), LocalDate.now(clock))
            .stream().map(mapper::toDto).toList();
    }

    @Override
    public BloodUnitResponseDTO discardUnit(UUID unitId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "transfusion.unit.discardReasonRequired");
        }
        unitRepository.lockById(unitId);
        BloodUnit unit = loadUnitScoped(unitId);
        if (unit.getStatus() == BloodUnitStatus.TRANSFUSED) {
            throw new BusinessException("transfusion.unit.alreadyTransfused", unit.getUnitNumber());
        }
        // QA D1186 class (round 22): a discarded unit stays discarded; a second
        // discard overwrote the reason recorded for the destruction.
        if (unit.getStatus() == BloodUnitStatus.DISCARDED) {
            throw new BusinessException("transfusion.unit.alreadyDiscarded", unit.getUnitNumber());
        }
        // QA CX-008 sibling: a bag running into a patient is stopped at the
        // bedside, which is what discards it; the stock screen cannot.
        if (administrationRepository.findByBloodUnit_Id(unit.getId())
                .filter(TransfusionAdministration::isInProgress).isPresent()) {
            throw new BusinessException("transfusion.unit.inProgress", unit.getUnitNumber());
        }
        unit.setStatus(BloodUnitStatus.DISCARDED);
        unit.setDiscardReason(reason);
        return mapper.toDto(unitRepository.save(unit));
    }

    // ── Requests ────────────────────────────────────────────────────────

    @Override
    public TransfusionRequestResponseDTO createRequest(TransfusionRequestRequestDTO request) {
        UUID hospitalId = requireHospital();
        Patient patient = patientChartAccess.require(request.getPatientId(), hospitalId);

        TransfusionUrgency urgency = request.getUrgency() != null
            ? request.getUrgency() : TransfusionUrgency.ROUTINE;

        PatientBloodGroup group = bloodGroupRepository
            .findByPatient_IdAndHospital_IdAndSupersededFalse(patient.getId(), hospitalId)
            .orElse(null);
        // An EMERGENCY request is allowed to proceed with no type on file — that
        // is the whole point of emergency release, and refusing it would block a
        // resuscitation. Anything else needs the patient typed first.
        if (group == null && urgency != TransfusionUrgency.EMERGENCY) {
            throw new com.example.hms.exception.FieldBusinessException("urgency", "transfusion.request.noTypeAndScreen");
        }

        Encounter encounter = null;
        if (request.getEncounterId() != null) {
            encounter = encounterRepository.findById(request.getEncounterId())
                .filter(e -> e.getHospital() == null
                    || Objects.equals(e.getHospital().getId(), hospitalId))
                .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound", request.getEncounterId()));
        }

        // QA D901 (sibling ordering): blood cannot be needed by a time that has
        // already passed when it is asked for; that is a typing error.
        if (request.getRequiredBy() != null
                && request.getRequiredBy().isBefore(LocalDateTime.now(clock).minus(CLOCK_SKEW))) {
            throw new FieldBusinessException("requiredBy", "transfusion.request.requiredByInPast");
        }

        TransfusionRequest entity = TransfusionRequest.builder()
            .patient(patient)
            .hospital(hospitalRef(hospitalId))
            .encounter(encounter)
            .bloodGroup(group)
            .productType(request.getProductType())
            .unitsRequested(request.getUnitsRequested())
            .indication(request.getIndication())
            .urgency(urgency)
            .status(TransfusionRequestStatus.REQUESTED)
            .requestedBy(currentStaff(hospitalId))
            .requestedAt(LocalDateTime.now(clock))
            .requiredBy(request.getRequiredBy())
            .notes(request.getNotes())
            .build();

        TransfusionRequest saved = requestRepository.save(entity);
        if (urgency == TransfusionUrgency.EMERGENCY && group == null) {
            log.warn("EMERGENCY transfusion request {} raised with no type and screen on file; "
                + "only group O red cells can be released", saved.getId());
        }
        return toDetail(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public TransfusionRequestResponseDTO getRequest(UUID requestId) {
        return toDetail(loadRequestScoped(requestId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransfusionRequestResponseDTO> listRequests(String status) {
        UUID hospitalId = requireHospital();
        List<TransfusionRequest> requests = StringUtils.hasText(status)
            ? requestRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(
                hospitalId, parseRequestStatus(status))
            : requestRepository.findByHospital_IdOrderByRequestedAtDesc(hospitalId);
        return requests.stream().map(r -> mapper.toDto(r, List.of(), List.of())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransfusionRequestResponseDTO> listRequestsForPatient(UUID patientId) {
        UUID hospitalId = requireHospital();
        patientChartAccess.require(patientId, hospitalId);
        return requestRepository.findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(patientId, hospitalId)
            .stream().map(r -> mapper.toDto(r, List.of(), List.of())).toList();
    }

    @Override
    public TransfusionRequestResponseDTO cancelRequest(UUID requestId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "transfusion.request.cancelReasonRequired");
        }
        requestRepository.lockById(requestId);
        TransfusionRequest request = loadRequestScoped(requestId);
        if (request.isTerminal()) {
            throw new BusinessException("transfusion.request.closed");
        }
        // QA D930 class: a transfusion request is cancelled by the clinician who
        // asked for it (or a verified super-admin in global view); a request
        // written with no requester keeps the old rule. The request was
        // resolved in the caller's hospital first, so the refusal leaks nothing.
        // The refusal states the rule (requester only), not the generic "act
        // in your own name" of an identity mismatch.
        if (request.getRequestedBy() != null
                && !com.example.hms.security.CallerIdentity.isGlobalSuperAdmin()
                && !com.example.hms.security.CallerIdentity.isCaller(request.getRequestedBy())) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("transfusion.request.cancel.requesterOnly"));
        }
        // The bags on this request take turns with a hang or a release of them.
        unitRepository.lockByRequestId(requestId);
        // QA CX-008 sibling: blood is running. Cancelling would close the request
        // over a live transfusion and put the hung bag back into stock.
        if (administrationRepository.existsByRequest_IdAndStatus(requestId, TransfusionAdministrationStatus.IN_PROGRESS)) {
            throw new BusinessException("transfusion.request.inProgress");
        }
        // Release anything held for this patient rather than stranding it. A bag
        // that has been hung never goes back to stock, whatever its status reads.
        for (BloodUnit unit : unitRepository.findByRequest_IdOrderByUnitNumberAsc(requestId)) {
            boolean held = unit.getStatus() == BloodUnitStatus.CROSSMATCHED || unit.getStatus() == BloodUnitStatus.ISSUED;
            if (held && administrationRepository.findByBloodUnit_Id(unit.getId()).isEmpty()) {
                unit.setStatus(BloodUnitStatus.RETURNED);
                unitRepository.save(unit);
            }
        }
        request.setStatus(TransfusionRequestStatus.CANCELLED);
        request.setCancelReason(reason);
        return toDetail(requestRepository.save(request));
    }

    // ── Crossmatch and issue ────────────────────────────────────────────

    @Override
    public CrossmatchResponseDTO recordCrossmatch(UUID requestId, CrossmatchRequestDTO request) {
        requestRepository.lockById(requestId);
        unitRepository.lockById(request.getBloodUnitId());
        TransfusionRequest transfusionRequest = loadRequestScoped(requestId);
        BloodUnit unit = loadUnitScoped(request.getBloodUnitId());
        requireSameHospital(unit, transfusionRequest);
        boolean claimsCompatible = Boolean.TRUE.equals(request.getCompatible());

        // The bag's administration for THIS request, running or ended. What was transfused for this
        // request can still be found incompatible, on a closed request and a terminal bag: that
        // result has to be written and told. Nothing else reopens them.
        TransfusionAdministration hung = administrationFor(unit, requestId);
        boolean lateIncompatible = hung != null && !claimsCompatible;
        TransfusionCrossmatch onFile = crossmatchRepository
            .findByRequest_IdAndBloodUnit_Id(requestId, unit.getId()).orElse(null);
        if (incompatibleResultIsFixed(requestId, unit, hung, onFile)) {
            // One row per (request, unit), so a new result would erase this one. Refused rather than
            // kept as history: the row is what the bedside and the audit read, and there is no way
            // a later result can tell anyone that a bag already given, or released knowing, is fine.
            if (claimsCompatible) {
                throw new FieldBusinessException(FIELD_COMPATIBLE, "transfusion.crossmatch.incompatibleStands", unit.getUnitNumber());
            }
            return mapper.toDto(onFile);
        }
        if (transfusionRequest.isTerminal() && !lateIncompatible) {
            throw new BusinessException("transfusion.request.closed");
        }

        // A bag running into this patient: the result still has to be written, and told, whatever
        // the bag's date says by now (an emergency bag is crossmatched after it went up).
        TransfusionAdministration running = hung != null && hung.isInProgress() ? hung : null;
        if (running == null && !lateIncompatible && unit.isExpiredOn(LocalDate.now(clock))) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.expired", unit.getUnitNumber(),
                DisplayDates.date(unit.getExpiresOn()));
        }
        if (unit.isTerminal() && !lateIncompatible) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.noLongerUsable", unit.getUnitNumber());
        }
        if (unit.getProductType() != transfusionRequest.getProductType()) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.productMismatch", unit.getUnitNumber(),
                productLabel(unit.getProductType()), productLabel(transfusionRequest.getProductType()));
        }
        requireNotHeldElsewhere(unit, requestId, hung);
        requireInFuture(request.getExpiresAt());

        // QA CX-011: the patient's current screen. A request raised before a
        // repeat must neither stay blocked by the screen it replaced, nor be
        // crossmatched against a group the repeat corrected.
        // A closed request keeps the screen it was decided on, as history (moveOpenRequestsOnto).
        PatientBloodGroup group = transfusionRequest.isTerminal()
            ? transfusionRequest.getBloodGroup() : currentScreen(transfusionRequest);
        // A current screen is what a COMPATIBLE verdict stands on. An incompatible one
        // stops a bag and needs none: refusing it let a bag found incompatible still hang.
        if (claimsCompatible && group == null) {
            throw new BusinessException("transfusion.crossmatch.noTypeAndScreen");
        }
        if (claimsCompatible && !group.screenIsCurrent(LocalDateTime.now(clock))) {
            throw new BusinessException("transfusion.crossmatch.screenLapsed");
        }

        boolean serologicallyCompatible = !claimsCompatible || serologicallyCompatible(group, unit, transfusionRequest);

        // THE rule. A tick box cannot overrule antigen biology: if the caller
        // says compatible and the ABO/Rh rules disagree, the write is refused
        // outright rather than warned about.
        //
        // ONE EXCEPTION, signed off 2026-08-25: for PLATELETS whose only
        // disagreement is the Rh restriction, a recorded reason permits the
        // pairing. The ABO exclusion (O platelets to an A or AB recipient) is
        // NOT overridable this way — an override reason is not a substitute
        // for the one ABO rule the protocol keeps.
        if (Boolean.TRUE.equals(request.getCompatible()) && !serologicallyCompatible) {
            boolean rhOnly = isPlateletRhOnlyMismatch(group, unit, transfusionRequest);
            boolean overridden = rhOnly && hasText(request.getIncompatibilityReason());
            if (!overridden) {
                throw new com.example.hms.exception.FieldBusinessException("incompatibilityReason", rhOnly ? "transfusion.crossmatch.incompatibleRhOverridable" : "transfusion.crossmatch.incompatible",
                    unit.getUnitNumber(),
                    groupLabel(unit.getAboGroup(), unit.getRhFactor()),
                    groupLabel(group.getAboGroup(), group.getRhFactor()),
                    productLabel(transfusionRequest.getProductType()));
            }
            log.warn("Platelet Rh restriction overridden for request {} with unit {}: {}",
                transfusionRequest.getId(), unit.getUnitNumber(), request.getIncompatibilityReason());
        }

        // The one pairing the protocol permits and nobody has confirmed was
        // meant: O platelets to a B recipient (open question, 2026-08-26).
        // Logged, never refused — the sign-off permits it. Logging it is how
        // the question gets an answer: a haematologist deciding whether the
        // asymmetry was intended needs to know how often it actually arises,
        // and a javadoc cannot tell them that.
        if (Boolean.TRUE.equals(request.getCompatible())
                && AboGroup.isPlateletPairingPendingConfirmation(
                    group.getAboGroup(), unit.getAboGroup(),
                    transfusionRequest.getProductType())) {
            log.info("Platelet pairing pending clinical confirmation: group O unit {} crossmatched "
                + "compatible for group B recipient on request {}. Permitted by the 2026-08-25 "
                + "sign-off; whether the O-to-B exception is intended is still open.",
                unit.getUnitNumber(), transfusionRequest.getId());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        TransfusionCrossmatch crossmatch = onFile != null ? onFile : TransfusionCrossmatch.builder()
            .request(transfusionRequest)
            .bloodUnit(unit)
            .hospital(transfusionRequest.getHospital())
            .build();
        boolean alreadyIncompatible = crossmatch.getId() != null && !Boolean.TRUE.equals(crossmatch.getCompatible());

        crossmatch.setCompatible(request.getCompatible());
        crossmatch.setMethod(request.getMethod());
        crossmatch.setIncompatibilityReason(request.getIncompatibilityReason());
        crossmatch.setPerformedBy(currentStaff(transfusionRequest.getHospital().getId()));
        crossmatch.setPerformedAt(now);
        crossmatch.setExpiresAt(request.getExpiresAt() != null
            ? request.getExpiresAt() : now.plusHours(DEFAULT_VALIDITY_HOURS));

        TransfusionCrossmatch saved = crossmatchRepository.save(crossmatch);

        if (Boolean.TRUE.equals(request.getCompatible())) {
            // A bag already out keeps its status: this is a result on it, not a new reservation.
            if (unit.getStatus() != BloodUnitStatus.ISSUED) {
                unit.setStatus(BloodUnitStatus.CROSSMATCHED);
            }
            unit.setRequest(transfusionRequest);
            unitRepository.save(unit);
            if (transfusionRequest.getStatus() == TransfusionRequestStatus.REQUESTED) {
                transfusionRequest.setStatus(TransfusionRequestStatus.CROSSMATCHED);
                requestRepository.save(transfusionRequest);
            }
        } else {
            if ((unit.getStatus() == BloodUnitStatus.ISSUED || hung != null) && !alreadyIncompatible) {
                notifySafely(() -> fileIncompatibleResultAfterCommit(transfusionRequest, unit, hung, saved), saved.getId());
            }
            // A running bag stays what it is: it is not back at the lab, and only the
            // clinician stops it. Stopping or finishing it closes it as any other. A bag whose
            // transfusion ended keeps its final status, and so does its request.
            if (hung == null
                    && (unit.getStatus() == BloodUnitStatus.CROSSMATCHED || unit.getStatus() == BloodUnitStatus.ISSUED)) {
                // A bag reserved here, or out and not hung, and now found incompatible goes
                // back to the lab. Left as it was, nothing could ever release it: issue and
                // hang refuse it, and it is hidden from the units that can be crossmatched.
                unit.setStatus(BloodUnitStatus.RETURNED);
                unitRepository.save(unit);
            }
        }
        return mapper.toDto(saved);
    }

    /**
     * The result is settled for this bag: an incompatible result stands once the bag was hung for
     * this request, or released knowing it. Before that the laboratory may still repeat the test.
     */
    private boolean incompatibleResultIsFixed(UUID requestId, BloodUnit unit, TransfusionAdministration hung,
                                              TransfusionCrossmatch onFile) {
        if (onFile == null || Boolean.TRUE.equals(onFile.getCompatible())) {
            return false;
        }
        return hung != null || releaseRepository.existsByRequest_IdAndBloodUnit_IdAndReleaseType(
            requestId, unit.getId(), EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE);
    }

    /**
     * An incompatible result on a bag out of the lab: URGENT, to the clinician who asked for the
     * blood and, when it was hung, to the nurse who ran it, each in their own language. Read here,
     * in the transaction; filed after the commit in a transaction of its own, so a failure here
     * leaves the result recorded. An emergency request can have no requester (a caller with no
     * staff record); then the hospital's lab directors are told as well.
     */
    private void fileIncompatibleResultAfterCommit(TransfusionRequest request, BloodUnit unit,
                                                   TransfusionAdministration hung, TransfusionCrossmatch crossmatch) {
        Set<UUID> recipients = new java.util.LinkedHashSet<>();
        addRecipient(recipients, request.getRequestedBy());
        if (recipients.isEmpty()) {
            recipients.addAll(labDirectors(request));
        }
        if (hung != null) {
            addRecipient(recipients, hung.getAdministeredBy());
        }
        UUID crossmatchId = crossmatch.getId();
        if (recipients.isEmpty()) {
            log.warn("Incompatible crossmatch {} on a bag out of the lab has nobody to tell", crossmatchId);
            return;
        }
        Patient patient = request.getPatient();
        Object[] titleArgs = {unit.getUnitNumber()};
        Object[] bodyArgs = {unit.getUnitNumber(), patient != null ? patient.getFullName() : null};
        // One item per result: the in-basket keeps one item per reference and reader, and a bag can be
        // found incompatible again on this row after a repeat test said otherwise.
        UUID resultReference = UUID.nameUUIDFromBytes((crossmatchId + "@" + crossmatch.getPerformedAt())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<com.example.hms.payload.dto.clinical.CreateInBasketItemRequest> items = recipients.stream()
            .map(recipient -> {
                com.example.hms.payload.dto.clinical.CreateInBasketItemRequest item = urgentItem(recipient, request,
                    com.example.hms.enums.InBasketItemType.RESULT, resultReference, CROSSMATCH_REFERENCE_TYPE);
                item.setTitleKey(INCOMPATIBLE_TITLE_KEY);
                if (hung == null) {
                    item.setBodyKey(INCOMPATIBLE_ISSUED_BODY_KEY);
                } else if (hung.isInProgress()) {
                    item.setBodyKey(INCOMPATIBLE_RUNNING_BODY_KEY);
                } else {
                    item.setBodyKey(INCOMPATIBLE_FINISHED_BODY_KEY);
                }
                return staffCopy(item, titleArgs, bodyArgs);
            })
            .toList();
        fileAfterCommit(items, crossmatchId);
    }

    /**
     * Every use of the serologically incompatible release: an URGENT review item for each of the
     * hospital's lab directors, after the commit. A failure leaves the release recorded.
     */
    private void fileReleaseReviewAfterCommit(TransfusionRequest request, BloodUnit unit, EmergencyBloodRelease release) {
        UUID releaseId = release.getId();
        List<UUID> reviewers = labDirectors(request);
        if (reviewers.isEmpty()) {
            // Nobody holds the role here. The person who authorised it is the one left who can answer.
            log.warn("Serologically incompatible release {} has no lab director: its authoriser is told", releaseId);
            reviewers = List.of(release.getAuthoriser().getId());
        }
        Patient patient = request.getPatient();
        Object[] titleArgs = {unit.getUnitNumber()};
        Object[] bodyArgs = {unit.getUnitNumber(), patient != null ? patient.getFullName() : null,
            com.example.hms.mapper.TransfusionMapper.userName(release.getAuthoriser())};
        fileAfterCommit(reviewers.stream()
            .map(reviewer -> {
                com.example.hms.payload.dto.clinical.CreateInBasketItemRequest item = urgentItem(reviewer, request,
                    com.example.hms.enums.InBasketItemType.TASK, releaseId, RELEASE_REFERENCE_TYPE);
                item.setTitleKey(RELEASE_REVIEW_TITLE_KEY);
                item.setBodyKey(RELEASE_REVIEW_BODY_KEY);
                return staffCopy(item, titleArgs, bodyArgs);
            })
            .toList(), releaseId);
    }

    /**
     * Composing runs in the transaction of what it reports, and a failure of its own (a message that
     * will not render) must not undo that. A failed database read has already doomed the transaction:
     * then the whole write fails and is reported as failed, leaving no record without its notice.
     */
    private static void notifySafely(Runnable compose, UUID referenceId) {
        try {
            compose.run();
        } catch (RuntimeException ex) {
            log.error("Notification not composed for {}: {}", referenceId, ex.getClass().getSimpleName());
        }
    }

    private List<UUID> labDirectors(TransfusionRequest request) {
        return assignmentRepository
            .findLiveUsersAtHospitalWithRoleCodes(request.getHospital().getId(), LAB_DIRECTOR_ROLE_CODES)
            .stream().map(com.example.hms.model.User::getId).distinct().toList();
    }

    private void fileAfterCommit(List<com.example.hms.payload.dto.clinical.CreateInBasketItemRequest> items, UUID referenceId) {
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            for (com.example.hms.payload.dto.clinical.CreateInBasketItemRequest item : items) {
                try {
                    inBasketService.createItemInNewTransaction(item);
                } catch (RuntimeException ex) {
                    log.warn("In-basket item not filed for {} {}: {}", item.getReferenceType(), referenceId,
                        ex.getClass().getSimpleName());
                }
            }
        });
    }

    /** One reader's item, before its keys; the keys render it in their language. */
    private static com.example.hms.payload.dto.clinical.CreateInBasketItemRequest urgentItem(
            UUID recipient, TransfusionRequest request, com.example.hms.enums.InBasketItemType type,
            UUID referenceId, String referenceType) {
        Patient patient = request.getPatient();
        com.example.hms.payload.dto.clinical.CreateInBasketItemRequest item =
            new com.example.hms.payload.dto.clinical.CreateInBasketItemRequest();
        item.setRecipientUserId(recipient);
        item.setHospitalId(request.getHospital().getId());
        item.setItemType(type);
        item.setPriority(com.example.hms.enums.InBasketPriority.URGENT);
        item.setReferenceId(referenceId);
        item.setReferenceType(referenceType);
        item.setEncounterId(request.getEncounter() != null ? request.getEncounter().getId() : null);
        item.setPatientId(patient != null ? patient.getId() : null);
        item.setPatientName(patient != null ? patient.getFullName() : null);
        return item;
    }

    /** The arguments, and the stored copy in the staff language for a reader with no language. */
    private static com.example.hms.payload.dto.clinical.CreateInBasketItemRequest staffCopy(
            com.example.hms.payload.dto.clinical.CreateInBasketItemRequest item, Object[] titleArgs, Object[] bodyArgs) {
        item.setTitleArgs(titleArgs);
        item.setTitle(MessageUtil.resolve(com.example.hms.service.i18n.NotificationLocales.STAFF, item.getTitleKey(), titleArgs));
        item.setBodyArgs(bodyArgs);
        item.setBody(MessageUtil.resolve(com.example.hms.service.i18n.NotificationLocales.STAFF, item.getBodyKey(), bodyArgs));
        return item;
    }

    private static void addRecipient(Set<UUID> recipients, Staff staff) {
        if (staff != null && staff.getUser() != null) {
            recipients.add(staff.getUser().getId());
        }
    }

    /**
     * The one row of an emergency release, and its audit after the commit: who released which unit
     * against which request, of which type, ids only: the callback has no session.
     */
    private EmergencyBloodRelease recordRelease(TransfusionRequest request, BloodUnit unit, EmergencyReleaseType type,
                                                String reason, UUID authoriserUserId, List<BloodUnit> negativeShown) {
        UUID actorUserId = roleValidator.getCurrentUserId();
        EmergencyBloodRelease saved = releaseRepository.save(EmergencyBloodRelease.builder()
            .request(request)
            .bloodUnit(unit)
            .hospital(request.getHospital())
            .releaseType(type)
            .reason(reason)
            .authoriser(authoriserUserId != null ? userRepository.getReferenceById(authoriserUserId) : null)
            .issuedBy(actorUserId != null ? userRepository.getReferenceById(actorUserId) : null)
            .issuedAt(LocalDateTime.now(clock))
            .antibodyWarning(positiveScreenOnRecord(request) != null)
            .negativeUnobtainableStated(!negativeShown.isEmpty())
            .negativeUnitsShown(negativeShown.isEmpty() ? null
                : negativeShown.stream().map(BloodUnit::getUnitNumber).collect(java.util.stream.Collectors.joining(", ")))
            .build());
        UUID hospitalId = request.getHospital().getId();
        UUID requestId = request.getId();
        UUID unitId = unit.getId();
        String description = releaseAuditDescription(type, unitId, requestId, negativeShown);
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                    .userId(actorUserId)
                    .hospitalId(hospitalId)
                    .eventType(com.example.hms.enums.AuditEventType.EMERGENCY_BLOOD_RELEASED)
                    .status(com.example.hms.enums.AuditStatus.SUCCESS)
                    .eventDescription(description)
                    .entityType(REQUEST_ENTITY_TYPE)
                    .resourceId(requestId.toString())
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Audit not written for the emergency release of unit {} against request {}: {}",
                    unitId, requestId, ex.getClass().getSimpleName());
            }
        });
        return saved;
    }

    /** Each type its own sentence: the audit screen renders them in the reader's language by pattern. */
    private static String releaseAuditDescription(EmergencyReleaseType type, UUID unitId, UUID requestId, List<BloodUnit> negativeShown) {
        String release = switch (type) {
            case UNCROSSMATCHED -> "Emergency uncrossmatched release";
            case O_POSITIVE_DECISION -> "Emergency release on a documented O positive decision";
            case SEROLOGICALLY_INCOMPATIBLE -> "Emergency release, serologically incompatible,";
        };
        String description = release + " of blood unit " + unitId + " against transfusion request " + requestId;
        return negativeShown.isEmpty() ? description
            : description + ", O negative stated not obtainable in time, stock shown "
                + negativeShown.stream().map(bag -> bag.getId().toString()).collect(java.util.stream.Collectors.joining(","));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CrossmatchResponseDTO> listCrossmatches(UUID requestId) {
        loadRequestScoped(requestId);
        return crossmatchRepository.findByRequest_IdOrderByPerformedAtDesc(requestId)
            .stream().map(mapper::toDto).toList();
    }

    @Override
    public BloodUnitResponseDTO issueUnit(UUID requestId, UUID unitId) {
        requestRepository.lockById(requestId);
        unitRepository.lockById(unitId);
        TransfusionRequest request = loadRequestScoped(requestId);
        BloodUnit unit = issuableUnit(request, unitId);

        TransfusionCrossmatch crossmatch = crossmatchRepository
            .findByRequest_IdAndBloodUnit_Id(requestId, unitId).orElse(null);
        BusinessException refusal = standingCrossmatchRefusal(request, unit, crossmatch);
        if (refusal != null) {
            // Emergency release: group O red cells may go out uncrossmatched, because in a
            // haemorrhage the crossmatch takes longer than the patient has. Anything else must
            // be crossmatched first, and an incompatible bag only leaves by emergencyRelease.
            requireUncrossmatchedRelease(request, unit, crossmatch, refusal);
            log.warn("EMERGENCY uncrossmatched release of unit {} against request {}",
                unit.getUnitNumber(), requestId);
            recordRelease(request, unit, EmergencyReleaseType.UNCROSSMATCHED, null, null, List.of());
        }
        return putOut(request, unit);
    }

    @Override
    public BloodUnitResponseDTO emergencyRelease(UUID requestId, EmergencyReleaseRequestDTO release) {
        String reason = release.getReason() == null ? "" : release.getReason().trim();
        if (reason.isEmpty()) {
            throw new FieldBusinessException(FIELD_REASON, "transfusion.emergency.reasonRequired");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new FieldBusinessException(FIELD_REASON, "transfusion.emergency.reasonTooLong", MAX_REASON_LENGTH);
        }
        if (release.getAuthoriserUserId() == null) {
            throw new FieldBusinessException(FIELD_AUTHORISER, "transfusion.emergency.authoriserRequired");
        }
        if (release.getReleaseType() == EmergencyReleaseType.UNCROSSMATCHED) {
            throw new FieldBusinessException(FIELD_RELEASE_TYPE, "transfusion.emergency.typeNotDocumented");
        }
        if (roleValidator.getCurrentUserId() == null) {
            // A documented release names who issued it: an actor nobody can name cannot make one.
            throw new AccessDeniedException("A documented emergency release needs an identified caller.");
        }
        requestRepository.lockById(requestId);
        unitRepository.lockById(release.getBloodUnitId());
        TransfusionRequest request = loadRequestScoped(requestId);
        if (!request.isEmergency()) {
            throw new BusinessException("transfusion.emergency.notEmergency");
        }
        BloodUnit unit = issuableUnit(request, release.getBloodUnitId());
        if (!AboGroup.isEmergencyRedCells(request.getProductType())) {
            throw new BusinessException("transfusion.emergency.redCellsOnly");
        }
        TransfusionCrossmatch crossmatch = crossmatchRepository
            .findByRequest_IdAndBloodUnit_Id(requestId, unit.getId()).orElse(null);
        if (unit.getStatus() == BloodUnitStatus.ISSUED && documentedRelease(request, unit) != null) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.emergency.alreadyReleased", unit.getUnitNumber());
        }
        if (release.getReleaseType() == EmergencyReleaseType.O_POSITIVE_DECISION) {
            requireOPositiveDecisionAllowed(request, unit, crossmatch);
        } else {
            requireIncompatibleReleaseAllowed(request, unit, crossmatch);
        }
        List<BloodUnit> negativeShown = negativeStockAhead(request, unit);
        if (!negativeShown.isEmpty() && !Boolean.TRUE.equals(release.getNegativeUnobtainable())) {
            throw new FieldBusinessException(FIELD_NEGATIVE_UNOBTAINABLE, "transfusion.emergency.negativeStatementRequired",
                unit.getUnitNumber(), negativeShown.stream().map(BloodUnit::getUnitNumber).collect(java.util.stream.Collectors.joining(", ")));
        }
        requireAnswerableAuthoriser(request, release);
        log.warn("EMERGENCY {} release of unit {} against request {}",
            release.getReleaseType(), unit.getUnitNumber(), requestId);
        EmergencyBloodRelease saved = recordRelease(request, unit, release.getReleaseType(), reason, release.getAuthoriserUserId(), negativeShown);
        if (release.getReleaseType() == EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE) {
            notifySafely(() -> fileReleaseReviewAfterCommit(request, unit, saved), saved.getId());
        }
        return putOut(request, unit);
    }

    /** What every way out of the lab checks; the caller locked the bag's row before reading anything. */
    private BloodUnit issuableUnit(TransfusionRequest request, UUID unitId) {
        if (request.isTerminal()) {
            throw new BusinessException("transfusion.request.closed");
        }
        BloodUnit unit = loadUnitScoped(unitId);
        requireSameHospital(unit, request);
        if (unit.isExpiredOn(LocalDate.now(clock))) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.expired", unit.getUnitNumber(),
                DisplayDates.date(unit.getExpiresOn()));
        }
        // A bag that was transfused, discarded or retired cannot go out again,
        // whatever crossmatch it still has on file.
        if (unit.isTerminal()) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.noLongerUsable", unit.getUnitNumber());
        }
        // The crossmatch path has always checked the product; the emergency path never reached it.
        if (unit.getProductType() != request.getProductType()) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.productMismatch", unit.getUnitNumber(),
                productLabel(unit.getProductType()), productLabel(request.getProductType()));
        }
        // Emergency release takes no crossmatch, so nothing else stops it taking a bag
        // held for another patient, or one already running into them.
        requireNotHeldByAnother(unit, request.getId());
        if (administrationRepository.findByBloodUnit_Id(unitId).isPresent()) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.alreadyHung", unit.getUnitNumber());
        }
        return unit;
    }

    /** A global-view caller has no scope to stop it: a bag is only worked on for a request of its own hospital. */
    private static void requireSameHospital(BloodUnit unit, TransfusionRequest request) {
        if (unit.getHospital() != null && request.getHospital() != null
                && !Objects.equals(unit.getHospital().getId(), request.getHospital().getId())) {
            throw new ResourceNotFoundException(MSG_UNIT_NOT_FOUND, unit.getId());
        }
    }

    /** Why the crossmatch on file does not let this bag go out by itself; null when it does. */
    private BusinessException standingCrossmatchRefusal(TransfusionRequest request, BloodUnit unit,
                                                        TransfusionCrossmatch crossmatch) {
        LocalDateTime now = LocalDateTime.now(clock);
        return crossmatch == null || !crossmatch.isUsableAt(now)
            ? issueRefusal(unit, crossmatch)
            : crossmatchBasisRefusal(request, unit, crossmatch, now);
    }

    /** The one place the bag leaves the lab. */
    private BloodUnitResponseDTO putOut(TransfusionRequest request, BloodUnit unit) {
        unit.setStatus(BloodUnitStatus.ISSUED);
        unit.setRequest(request);
        BloodUnit saved = unitRepository.save(unit);

        if (request.getStatus() != TransfusionRequestStatus.ISSUED) {
            request.setStatus(TransfusionRequestStatus.ISSUED);
            requestRepository.save(request);
        }
        return mapper.toDto(saved);
    }

    /**
     * The release that takes no decision: O negative as it always was, or O positive for an adult
     * male the record says nothing against. Everything else is refused, naming the next step.
     */
    private void requireUncrossmatchedRelease(TransfusionRequest request, BloodUnit unit,
                                              TransfusionCrossmatch crossmatch, BusinessException refusal) {
        requireNotFoundIncompatibleElsewhere(request, unit, crossmatch);
        if (unit.getStatus() == BloodUnitStatus.ISSUED
                && releaseRepository.findFirstByRequest_IdAndBloodUnit_IdOrderByIssuedAtDesc(request.getId(), unit.getId()).isPresent()) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.emergency.alreadyReleased", unit.getUnitNumber());
        }
        if (emergencyReleaseAllowed(request, unit, crossmatch)) {
            return;
        }
        boolean incompatibleOnFile = crossmatch != null && !Boolean.TRUE.equals(crossmatch.getCompatible());
        if (!request.isEmergency() || incompatibleOnFile
                || !AboGroup.isEmergencyOPositive(unit.getAboGroup(), unit.getRhFactor(), unit.getProductType())) {
            throw refusal;
        }
        if (oPositiveStep(request) != EmergencyReleasePolicy.OPositiveStep.NONE) {
            throw new FieldBusinessException(FIELD_AUTHORISER, "transfusion.emergency.decisionRequired", unit.getUnitNumber());
        }
    }

    /**
     * A bag found incompatible for this patient on another of their requests is the same bag and the
     * same patient. It goes out only as what it is: that result recorded on this request, then the
     * serologically incompatible release. A repeat crossmatch here that finds it compatible stands
     * on its own and never reaches this check.
     */
    private void requireNotFoundIncompatibleElsewhere(TransfusionRequest request, BloodUnit unit, TransfusionCrossmatch crossmatch) {
        boolean onThisRequest = crossmatch != null && !Boolean.TRUE.equals(crossmatch.getCompatible());
        if (!onThisRequest && foundIncompatibleForPatient(request, unit)) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.emergency.incompatibleForPatient", unit.getUnitNumber());
        }
    }

    private boolean foundIncompatibleForPatient(TransfusionRequest request, BloodUnit unit) {
        return request.getPatient() != null
            && crossmatchRepository.findIncompatibleUnitIdsForPatient(request.getPatient().getId()).contains(unit.getId());
    }

    private void requireOPositiveDecisionAllowed(TransfusionRequest request, BloodUnit unit, TransfusionCrossmatch crossmatch) {
        if (!AboGroup.isEmergencyOPositive(unit.getAboGroup(), unit.getRhFactor(), unit.getProductType())) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.emergency.notOPositive", unit.getUnitNumber());
        }
        requireNotFoundIncompatibleElsewhere(request, unit, crossmatch);
        if (crossmatch != null && !Boolean.TRUE.equals(crossmatch.getCompatible())) {
            throw new BusinessException("transfusion.issue.crossmatchIncompatible", unit.getUnitNumber());
        }
    }

    /**
     * Despite an incompatible crossmatch, never despite the patient's group: the guidance speaks of a
     * "serologically incompatible crossmatch", so an ABO-excluded bag is not released by this action,
     * and O positive keeps its own rule. The result stays exactly as the laboratory recorded it.
     */
    private void requireIncompatibleReleaseAllowed(TransfusionRequest request, BloodUnit unit, TransfusionCrossmatch crossmatch) {
        requireNotFoundIncompatibleElsewhere(request, unit, crossmatch);
        if (crossmatch == null || Boolean.TRUE.equals(crossmatch.getCompatible())) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.emergency.noIncompatibleResult", unit.getUnitNumber());
        }
        if (!documentedReleaseAllows(request, unit)) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.emergency.groupExcludes", unit.getUnitNumber());
        }
    }

    /**
     * The O negative this O positive bag would go out ahead of, by what the system shows: empty when
     * the bag is not O positive, or this patient does not take O negative first. The system can be
     * wrong (a bag recorded as returned can still be on the ward), so this asks for a statement and
     * never refuses outright: the documented decision must not become a block on transfusion.
     */
    private List<BloodUnit> negativeStockAhead(TransfusionRequest request, BloodUnit unit) {
        if (!AboGroup.isEmergencyOPositive(unit.getAboGroup(), unit.getRhFactor(), unit.getProductType())
                || oPositiveStep(request) != EmergencyReleasePolicy.OPositiveStep.O_NEGATIVE_FIRST) {
            return List.of();
        }
        return negativeStock(request);
    }

    private List<BloodUnit> negativeStock(TransfusionRequest request) {
        return unitRepository.findStockForPatient(request.getHospital().getId(), request.getPatient().getId(), request.getProductType(),
            AboGroup.emergencyReleaseGroup(), AboGroup.emergencyReleaseRh(), LocalDate.now(clock));
    }

    /** The named person holds a live role here; for an incompatible release, a doctor or lab director who is not the issuer. */
    private void requireAnswerableAuthoriser(TransfusionRequest request, EmergencyReleaseRequestDTO release) {
        boolean incompatible = release.getReleaseType() == EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE;
        if (incompatible && Objects.equals(release.getAuthoriserUserId(), roleValidator.getCurrentUserId())) {
            throw new FieldBusinessException(FIELD_AUTHORISER, "transfusion.emergency.authoriserNotIssuer");
        }
        Set<String> roles = incompatible
            ? EmergencyReleasePolicy.INCOMPATIBLE_AUTHORISER_ROLE_CODES
            : EmergencyReleasePolicy.O_POSITIVE_DECIDER_ROLE_CODES;
        if (!assignmentRepository.existsActiveWitness(release.getAuthoriserUserId(), request.getHospital().getId(), roles)) {
            throw new FieldBusinessException(FIELD_AUTHORISER, "transfusion.emergency.authoriserInvalid");
        }
    }

    private EmergencyReleasePolicy.OPositiveStep oPositiveStep(TransfusionRequest request) {
        Patient patient = request.getPatient();
        PatientBloodGroup screen = lookupScreen(request);
        return EmergencyReleasePolicy.oPositiveStep(
            patient != null ? patient.getGender() : null,
            patient != null ? patient.getDateOfBirth() : null,
            LocalDate.now(clock),
            screen != null ? screen.getRhFactor() : null,
            positiveScreenOnRecord(request) != null);
    }

    /**
     * The newest positive antibody screen on this patient's record here, superseded ones included:
     * an antibody once found stays known when a later screen no longer detects it. The record codes
     * the screen's result and leaves the antibody's identity to free text, which is not parsed: a
     * positive screen stands for known anti-D. Null when there is none.
     */
    private PatientBloodGroup positiveScreenOnRecord(TransfusionRequest request) {
        PatientBloodGroup current = lookupScreen(request);
        if (current != null && current.getAntibodyScreen() == AntibodyScreenResult.POSITIVE) {
            return current;
        }
        if (request.getPatient() == null || request.getHospital() == null) {
            return null;
        }
        return bloodGroupRepository
            .findByPatient_IdAndHospital_IdOrderByPerformedAtDesc(request.getPatient().getId(), request.getHospital().getId())
            .stream().filter(group -> group.getAntibodyScreen() == AntibodyScreenResult.POSITIVE)
            .findFirst().orElse(null);
    }

    /** The current screen, without moving the request onto it: this one only reads. */
    private PatientBloodGroup lookupScreen(TransfusionRequest request) {
        if (request.getPatient() == null || request.getHospital() == null) {
            return request.getBloodGroup();
        }
        return bloodGroupRepository
            .findByPatient_IdAndHospital_IdAndSupersededFalse(request.getPatient().getId(), request.getHospital().getId())
            .orElse(request.getBloodGroup());
    }

    private boolean documentedReleaseAllows(TransfusionRequest request, BloodUnit unit) {
        PatientBloodGroup screen = lookupScreen(request);
        return AboGroup.documentedReleaseAllows(
            screen != null ? screen.getAboGroup() : null, screen != null ? screen.getRhFactor() : null,
            unit.getAboGroup(), unit.getRhFactor(), unit.getProductType());
    }

    @Override
    @Transactional(readOnly = true)
    public EmergencyReleaseOptionsDTO emergencyReleaseOptions(UUID requestId) {
        TransfusionRequest request = loadRequestScoped(requestId);
        UUID hospitalId = request.getHospital().getId();
        PatientBloodGroup positive = positiveScreenOnRecord(request);
        EmergencyReleasePolicy.OPositiveStep step = oPositiveStep(request);
        List<BloodUnit> shown = step == EmergencyReleasePolicy.OPositiveStep.O_NEGATIVE_FIRST ? negativeStock(request) : List.of();
        return EmergencyReleaseOptionsDTO.builder()
            .antibodyWarning(positive != null)
            .antibodyDetail(positive != null ? positive.getAntibodyDetail() : null)
            .negativeStock(shown.stream().map(bag -> EmergencyReleaseOptionsDTO.Unit.builder()
                .id(bag.getId()).unitNumber(bag.getUnitNumber())
                .aboGroup(bag.getAboGroup()).rhFactor(bag.getRhFactor()).expiresOn(bag.getExpiresOn()).build()).toList())
            .units(uncrossmatchedOptions(request, step, !shown.isEmpty()))
            .incompatibleUnits(incompatibleOptions(request, !shown.isEmpty()))
            .deciders(candidates(hospitalId, EmergencyReleasePolicy.O_POSITIVE_DECIDER_ROLE_CODES, null))
            .authorisers(candidates(hospitalId, EmergencyReleasePolicy.INCOMPATIBLE_AUTHORISER_ROLE_CODES,
                roleValidator.getCurrentUserId()))
            .build();
    }

    /**
     * Stock of this product in group O, plus what this request holds and could still need this form
     * for: a bag whose crossmatch no longer stands, or an O positive bag out with no decision that
     * now needs one. Nothing found incompatible for this patient: that has its own action.
     */
    private List<EmergencyReleaseOptionsDTO.Unit> uncrossmatchedOptions(TransfusionRequest request,
                                                                        EmergencyReleasePolicy.OPositiveStep step,
                                                                        boolean negativeShown) {
        LocalDate today = LocalDate.now(clock);
        boolean decisionStep = step != EmergencyReleasePolicy.OPositiveStep.NONE;
        Map<UUID, BloodUnit> bags = new LinkedHashMap<>();
        unitRepository.findByRequest_IdOrderByUnitNumberAsc(request.getId()).stream()
            .filter(bag -> !bag.isExpiredOn(today) && administrationRepository.findByBloodUnit_Id(bag.getId()).isEmpty())
            .filter(bag -> bag.getStatus() == BloodUnitStatus.CROSSMATCHED
                || (bag.getStatus() == BloodUnitStatus.ISSUED && decisionStep && documentedRelease(request, bag) == null
                    && AboGroup.isEmergencyOPositive(bag.getAboGroup(), bag.getRhFactor(), bag.getProductType())))
            .forEach(bag -> bags.put(bag.getId(), bag));
        unitRepository.findAssignable(request.getHospital().getId(), today).stream()
            .filter(bag -> bag.getProductType() == request.getProductType()
                && bag.getAboGroup() == AboGroup.emergencyReleaseGroup())
            .filter(bag -> administrationRepository.findByBloodUnit_Id(bag.getId()).isEmpty())
            .forEach(bag -> bags.put(bag.getId(), bag));
        List<UUID> incompatible = request.getPatient() == null ? List.of()
            : crossmatchRepository.findIncompatibleUnitIdsForPatient(request.getPatient().getId());
        List<EmergencyReleaseOptionsDTO.Unit> options = new ArrayList<>();
        for (BloodUnit bag : bags.values()) {
            boolean oPositive = AboGroup.isEmergencyOPositive(bag.getAboGroup(), bag.getRhFactor(), request.getProductType());
            boolean oNegative = bag.getAboGroup() == AboGroup.emergencyReleaseGroup()
                && bag.getRhFactor() == AboGroup.emergencyReleaseRh();
            if (bag.getProductType() == request.getProductType() && !incompatible.contains(bag.getId())
                    && (oNegative || oPositive)) {
                options.add(EmergencyReleaseOptionsDTO.Unit.builder()
                    .id(bag.getId()).unitNumber(bag.getUnitNumber())
                    .aboGroup(bag.getAboGroup()).rhFactor(bag.getRhFactor()).expiresOn(bag.getExpiresOn())
                    .decisionRequired(oPositive && decisionStep)
                    .statementRequired(oPositive && negativeShown)
                    .build());
            }
        }
        return options;
    }

    private List<EmergencyReleaseOptionsDTO.IncompatibleUnit> incompatibleOptions(TransfusionRequest request,
                                                                                  boolean negativeShown) {
        LocalDate today = LocalDate.now(clock);
        return crossmatchRepository.findByRequest_IdOrderByPerformedAtDesc(request.getId()).stream()
            .filter(result -> !Boolean.TRUE.equals(result.getCompatible()))
            .filter(result -> {
                BloodUnit bag = result.getBloodUnit();
                return bag.getProductType() == request.getProductType() && !bag.isTerminal() && !bag.isExpiredOn(today)
                    && bag.getStatus() != BloodUnitStatus.ISSUED
                    && administrationRepository.findByBloodUnit_Id(bag.getId()).isEmpty();
            })
            .map(result -> {
                BloodUnit bag = result.getBloodUnit();
                boolean heldBack = bag.getStatus() == BloodUnitStatus.CROSSMATCHED && bag.getRequest() != null
                    && !Objects.equals(bag.getRequest().getId(), request.getId());
                return EmergencyReleaseOptionsDTO.IncompatibleUnit.builder()
                    .id(bag.getId()).unitNumber(bag.getUnitNumber())
                    .aboGroup(bag.getAboGroup()).rhFactor(bag.getRhFactor())
                    .incompatibilityReason(result.getIncompatibilityReason())
                    .releasable(!heldBack && documentedReleaseAllows(request, bag))
                    .statementRequired(negativeShown
                        && AboGroup.isEmergencyOPositive(bag.getAboGroup(), bag.getRhFactor(), bag.getProductType()))
                    .build();
            })
            .toList();
    }

    /** The people the server will accept for these roles here, each once, by name. */
    private List<StockWitnessCandidateDTO> candidates(UUID hospitalId, Set<String> roles, UUID excluded) {
        Map<UUID, StockWitnessCandidateDTO> byUser = new LinkedHashMap<>();
        for (com.example.hms.model.User user : assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, roles)) {
            if (!user.getId().equals(excluded)) {
                byUser.put(user.getId(), new StockWitnessCandidateDTO(user.getId(), com.example.hms.mapper.TransfusionMapper.userName(user)));
            }
        }
        return byUser.values().stream()
            .sorted(java.util.Comparator.comparing(StockWitnessCandidateDTO::displayName,
                java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    // ── Bedside ─────────────────────────────────────────────────────────

    @Override
    public TransfusionAdministrationResponseDTO startAdministration(
        TransfusionAdministrationRequestDTO request) {

        if (request.getVerifiedByStaffId() == null && request.getVerifiedByUserId() == null) {
            throw new FieldBusinessException("verifiedByStaffId", "transfusion.administration.verifierRequired");
        }
        requestRepository.lockById(request.getRequestId());
        unitRepository.lockById(request.getBloodUnitId());
        TransfusionRequest transfusionRequest = loadRequestScoped(request.getRequestId());
        if (transfusionRequest.isTerminal()) {
            throw new FieldBusinessException("requestId", "transfusion.request.closed");
        }
        UUID hospitalId = transfusionRequest.getHospital().getId();
        BloodUnit unit = loadUnitScoped(request.getBloodUnitId());
        requireSameHospital(unit, transfusionRequest);

        if (unit.getStatus() != BloodUnitStatus.ISSUED) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.notIssued", unit.getUnitNumber());
        }
        // The bedside check is that THIS bag belongs to THIS patient: a bag issued
        // against another request is another patient's blood.
        if (unit.getRequest() != null && !Objects.equals(unit.getRequest().getId(), transfusionRequest.getId())) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.otherRequest", unit.getUnitNumber());
        }
        if (unit.isExpiredOn(LocalDate.now(clock))) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.expired", unit.getUnitNumber(),
                DisplayDates.date(unit.getExpiresOn()));
        }
        administrationRepository.findByBloodUnit_Id(unit.getId()).ifPresent(existing -> {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.alreadyHung", unit.getUnitNumber());
        });
        requireCrossmatchStillStands(transfusionRequest, unit);

        Staff administering = currentStaff(hospitalId);
        if (administering == null) {
            throw new AccessDeniedException(
                "Only a clinician with a staff profile at this hospital can transfuse.");
        }
        Staff verifier = (request.getVerifiedByUserId() != null
                ? staffRepository.findByUserIdAndHospitalId(request.getVerifiedByUserId(), hospitalId)
                : staffRepository.findById(request.getVerifiedByStaffId()))
            // QA D503 class: the second checker is an active staff member AT this hospital
            // (a record with no hospital is not one), and a different person from the caller.
            .filter(s -> s.getHospital() != null && Objects.equals(s.getHospital().getId(), hospitalId))
            .filter(Staff::isActive)
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound",
                request.getVerifiedByUserId() != null ? request.getVerifiedByUserId() : request.getVerifiedByStaffId()));
        // The independent second check. A transfusion is the one administration
        // where a single signature is not accepted practice anywhere. Another staff
        // record of the same person (a second hospital's, say) is still one person.
        boolean samePerson = Objects.equals(administering.getId(), verifier.getId())
            || (administering.getUser() != null && verifier.getUser() != null
                && Objects.equals(administering.getUser().getId(), verifier.getUser().getId()));
        if (samePerson) {
            throw new BusinessException("transfusion.administration.twoPersonCheck");
        }

        TransfusionAdministration administration = TransfusionAdministration.builder()
            .request(transfusionRequest)
            .bloodUnit(unit)
            .patient(transfusionRequest.getPatient())
            .hospital(transfusionRequest.getHospital())
            .status(TransfusionAdministrationStatus.IN_PROGRESS)
            .startedAt(LocalDateTime.now(clock))
            .administeredBy(administering)
            .verifiedBy(verifier)
            .verificationMethod(request.getVerificationMethod())
            .notes(request.getNotes())
            .build();

        TransfusionAdministration saved = administrationRepository.save(administration);
        return toDetail(saved, List.of());
    }

    /**
     * QA CX-008: who can be the second check at the bedside. The list a nurse picks
     * from on the MAR, on this module's own bedside gate (that one leaves out surgeons,
     * who hang blood here), and only people with an active staff record at this
     * hospital, because that is what the hang goes on to require.
     */
    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO> listVerifierCandidates() {
        UUID hospitalId = requireHospital();
        UUID self = roleValidator.getCurrentUserId();
        return staffRepository.findByHospital_IdIn(List.of(hospitalId)).stream()
            .filter(Staff::isActive)
            .filter(staff -> staff.getUser() != null && staff.getUser().isActive() && !staff.getUser().isDeleted()
                && !Objects.equals(staff.getUser().getId(), self))
            .filter(staff -> staff.getAssignment() != null && staff.getAssignment().getRole() != null
                && staff.getAssignment().getRole().getCode() != null
                && com.example.hms.service.emar.ControlledAdministrationWitness.WITNESS_ROLE_CODES
                    .contains(staff.getAssignment().getRole().getCode().toUpperCase(java.util.Locale.ROOT)))
            .map(staff -> new com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO(
                staff.getUser().getId(), verifierName(staff)))
            .sorted(java.util.Comparator.comparing(
                com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO::displayName,
                java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    private static String verifierName(Staff staff) {
        String first = staff.getUser().getFirstName();
        String last = staff.getUser().getLastName();
        String combined = ((first != null ? first.trim() : "") + " " + (last != null ? last.trim() : "")).trim();
        return combined.isEmpty() ? staff.getUser().getUsername() : combined;
    }

    @Override
    public TransfusionAdministrationResponseDTO completeAdministration(UUID administrationId, Integer volumeMl) {
        // Sibling of QA D883: the volume given cannot exceed what a bag holds.
        if (volumeMl != null && (volumeMl < 0 || volumeMl > MAX_UNIT_VOLUME_ML)) {
            throw new BusinessException("transfusion.administration.volumeOutOfRange");
        }
        lockAdministrationTargets(administrationId);
        TransfusionAdministration administration = loadAdministrationScoped(administrationId);
        if (!administration.isInProgress()) {
            throw new BusinessException("transfusion.administration.closed");
        }
        administration.setStatus(TransfusionAdministrationStatus.COMPLETED);
        administration.setCompletedAt(LocalDateTime.now(clock));
        administration.setVolumeTransfusedMl(volumeMl);

        BloodUnit unit = administration.getBloodUnit();
        unit.setStatus(BloodUnitStatus.TRANSFUSED);
        unitRepository.save(unit);

        completeRequestIfDone(administration.getRequest());
        return toDetail(administrationRepository.save(administration),
            reactionRepository.findByAdministration_IdOrderByOnsetAtDesc(administrationId));
    }

    @Override
    public TransfusionAdministrationResponseDTO stopAdministration(UUID administrationId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new FieldBusinessException(FIELD_REASON, "transfusion.administration.stopReasonRequired");
        }
        if (reason.trim().length() > MAX_REASON_LENGTH) {
            throw new FieldBusinessException(FIELD_REASON, "transfusion.administration.stopReasonTooLong", MAX_REASON_LENGTH);
        }
        lockAdministrationTargets(administrationId);
        TransfusionAdministration administration = loadAdministrationScoped(administrationId);
        if (!administration.isInProgress()) {
            throw new BusinessException("transfusion.administration.closed");
        }
        stopInternal(administration, reason.trim());
        // QA CX-008: a bag that was hung and stopped is opened: it never returns to
        // stock. It used to keep reading ISSUED, which offered "hang" again and left
        // the request open for good.
        discardHungUnit(administration.getBloodUnit(), reason.trim());
        completeRequestIfDone(administration.getRequest());
        return toDetail(administrationRepository.save(administration),
            reactionRepository.findByAdministration_IdOrderByOnsetAtDesc(administrationId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransfusionAdministrationResponseDTO> listAdministrationsForPatient(UUID patientId) {
        UUID hospitalId = requireHospital();
        patientChartAccess.require(patientId, hospitalId);
        return administrationRepository
            .findByPatient_IdAndHospital_IdOrderByStartedAtDesc(patientId, hospitalId)
            .stream()
            .map(a -> toDetail(a, reactionRepository.findByAdministration_IdOrderByOnsetAtDesc(a.getId())))
            .toList();
    }

    @Override
    public TransfusionReactionResponseDTO recordReaction(UUID administrationId,
                                                         TransfusionReactionRequestDTO request) {
        // QA D858: NURSE_A recorded a reaction with onsetAt = now + 24h and got
        // 201. A reaction is observed, so its onset is in the past (a few
        // minutes of clock skew between the ward PC and the server aside).
        requireNotInFuture(request.getOnsetAt(), "onsetAt", "transfusion.reaction.onsetInFuture");
        lockAdministrationTargets(administrationId);
        TransfusionAdministration administration = loadAdministrationScoped(administrationId);
        // QA D901: reaction 7b374e6a was saved with an onset ten days before
        // administration 36dc2b02 was hung. A reaction to a transfusion begins
        // once it has started; the ward PC's clock may lag the server's by
        // the usual skew, and no more.
        LocalDateTime startedAt = administration.getStartedAt();
        if (startedAt != null && request.getOnsetAt() != null
                && request.getOnsetAt().isBefore(startedAt.minus(CLOCK_SKEW))) {
            throw new FieldBusinessException("onsetAt", "transfusion.reaction.onsetBeforeStart");
        }

        TransfusionReaction reaction = TransfusionReaction.builder()
            .administration(administration)
            .patient(administration.getPatient())
            .hospital(administration.getHospital())
            .reactionType(request.getReactionType())
            .severity(request.getSeverity())
            .onsetAt(request.getOnsetAt())
            .signsSymptoms(request.getSignsSymptoms())
            .actionsTaken(request.getActionsTaken())
            .unitReturnedToLab(Boolean.TRUE.equals(request.getUnitReturnedToLab()))
            .reportedBy(currentStaff(administration.getHospital().getId()))
            .reportedAt(LocalDateTime.now(clock))
            .build();

        TransfusionReaction saved = reactionRepository.save(reaction);

        // The first step of every transfusion-reaction protocol is to stop the
        // infusion. A record that left the unit reading as still running while
        // a reaction was documented would describe something that must not
        // happen, so recording one stops it.
        if (administration.isInProgress()) {
            stopInternal(administration, "Transfusion reaction: " + request.getReactionType());
            administrationRepository.save(administration);
        }

        // The implicated bag is evidence for the workup, not stock. A delayed
        // reaction to a bag that ran to the end leaves it what it is: transfused.
        discardHungUnit(administration.getBloodUnit(), "Transfusion reaction (" + request.getReactionType() + ")");
        completeRequestIfDone(administration.getRequest());

        if (saved.isSevere()) {
            log.warn("SEVERE transfusion reaction {} on administration {} for patient {}",
                request.getReactionType(), administrationId,
                administration.getPatient() != null ? administration.getPatient().getId() : null);
        }
        return mapper.toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransfusionReactionResponseDTO> listReactionsForPatient(UUID patientId) {
        UUID hospitalId = requireHospital();
        patientChartAccess.require(patientId, hospitalId);
        return reactionRepository.findByPatient_IdAndHospital_IdOrderByOnsetAtDesc(patientId, hospitalId)
            .stream().map(mapper::toDto).toList();
    }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Why this unit cannot go out.
     *
     * <p>Three different situations, and the message has to name the right one:
     * nobody ever crossmatched it, the crossmatch was done and has since
     * lapsed, or the crossmatch came back incompatible. "Repeat it" is correct
     * advice for the second and dangerously wrong for the third, so they are
     * not collapsed.
     */
    private BusinessException issueRefusal(BloodUnit unit, TransfusionCrossmatch crossmatch) {
        if (crossmatch == null) {
            return new BusinessException("transfusion.issue.notCrossmatched", unit.getUnitNumber());
        }
        if (Boolean.TRUE.equals(crossmatch.getCompatible())) {
            return new BusinessException("transfusion.issue.crossmatchExpired", unit.getUnitNumber());
        }
        return new BusinessException("transfusion.issue.crossmatchIncompatible", unit.getUnitNumber());
    }

    /**
     * QA CX-011: a usable crossmatch stands on the screen behind it, so it is
     * worth nothing once that screen has lapsed, once a correction makes the
     * pairing one the ABO/Rh rules reject, or once a newer screen found antibodies
     * this crossmatch was never read against.
     */
    private BusinessException crossmatchBasisRefusal(TransfusionRequest request, BloodUnit unit,
                                                     TransfusionCrossmatch crossmatch, LocalDateTime now) {
        PatientBloodGroup screen = currentScreen(request);
        if (screen == null) {
            return null;
        }
        if (!screen.screenIsCurrent(now)) {
            return new BusinessException("transfusion.issue.screenLapsed", unit.getUnitNumber());
        }
        if (predatesNewAntibodies(screen, crossmatch) || !groupStillAllows(screen, unit, request, crossmatch)) {
            return new BusinessException("transfusion.issue.crossmatchOutdated", unit.getUnitNumber());
        }
        return null;
    }

    /**
     * The O negative release (O positive has its own rules, in {@link EmergencyReleasePolicy}).
     * O Rh-negative is the universal RED CELL donor. It is not a universal donor of plasma or
     * platelets, so those never go out uncrossmatched, typed patient or not. O negative red cells
     * pass every group, so this blocks no resuscitation. Nor does urgency overrule what was found: an incompatible result
     * for this bag, or a crossmatch older than antibodies found since, stops it here
     * at the lab, not at the bedside.
     */
    private boolean emergencyReleaseAllowed(TransfusionRequest request, BloodUnit unit,
                                            TransfusionCrossmatch crossmatch) {
        if (!request.isEmergency()
                || !AboGroup.isEmergencyRedCells(unit.getProductType())
                || unit.getAboGroup() != AboGroup.emergencyReleaseGroup()
                || unit.getRhFactor() != AboGroup.emergencyReleaseRh()) {
            return false;
        }
        PatientBloodGroup screen = currentScreen(request);
        if (crossmatch != null && !Boolean.TRUE.equals(crossmatch.getCompatible())) {
            return false;
        }
        return screen == null
            || (groupStillAllows(screen, unit, request, crossmatch)
                && (crossmatch == null || !predatesNewAntibodies(screen, crossmatch)));
    }

    private static boolean predatesNewAntibodies(PatientBloodGroup screen, TransfusionCrossmatch crossmatch) {
        return screen.getAntibodyScreen() == AntibodyScreenResult.POSITIVE
            && crossmatch.getPerformedAt() != null && screen.getPerformedAt() != null
            && crossmatch.getPerformedAt().isBefore(screen.getPerformedAt());
    }

    /**
     * QA CX-011: the last place a bag can be stopped. A crossmatch since found
     * incompatible, a group corrected after it, or antibodies it was never read
     * against all stop the bag. A screen that merely lapsed on the way does not
     * change what is in the bag. A bag released uncrossmatched in an emergency has
     * no crossmatch to stand on, so only the ABO/Rh rules apply once the patient is
     * typed. O negative red cells pass every group, so this blocks no resuscitation. A
     * release documented with a reason and a name is the exception: see documentedReleaseStands.
     */
    private void requireCrossmatchStillStands(TransfusionRequest request, BloodUnit unit) {
        TransfusionCrossmatch crossmatch = crossmatchRepository
            .findByRequest_IdAndBloodUnit_Id(request.getId(), unit.getId()).orElse(null);
        if (documentedReleaseStands(request, unit, crossmatch)) {
            return;
        }
        if (crossmatch == null) {
            if (foundIncompatibleForPatient(request, unit)) {
                throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.issue.crossmatchIncompatible",
                    unit.getUnitNumber());
            }
            PatientBloodGroup typedSince = currentScreen(request);
            if (typedSince != null && !serologicallyCompatible(typedSince, unit, request)) {
                throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.administration.groupExcludes",
                    unit.getUnitNumber());
            }
            return;
        }
        if (!Boolean.TRUE.equals(crossmatch.getCompatible())) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.issue.crossmatchIncompatible",
                unit.getUnitNumber());
        }
        PatientBloodGroup screen = currentScreen(request);
        if (screen != null
                && (predatesNewAntibodies(screen, crossmatch) || !groupStillAllows(screen, unit, request, crossmatch))) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.administration.groupChanged",
                unit.getUnitNumber());
        }
    }

    /**
     * Hang agrees with issue: a release documented with a reason and a name is not refused here by
     * the group re-check or the crossmatch check. It answers only for what it knew: an incompatible
     * result is covered by a serologically incompatible release made after it, and a correction that
     * puts the bag under an ABO exclusion stops it whatever was documented.
     */
    private boolean documentedReleaseStands(TransfusionRequest request, BloodUnit unit, TransfusionCrossmatch crossmatch) {
        EmergencyBloodRelease release = documentedRelease(request, unit);
        if (release == null) {
            return false;
        }
        if (crossmatch != null && !Boolean.TRUE.equals(crossmatch.getCompatible())
                && (release.getReleaseType() != EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE
                    || release.getIssuedAt().isBefore(crossmatch.getPerformedAt()))) {
            return false;
        }
        return documentedReleaseAllows(request, unit);
    }

    /** The latest release of this bag on this request, when it carried a reason and a name. */
    private EmergencyBloodRelease documentedRelease(TransfusionRequest request, BloodUnit unit) {
        return releaseRepository.findFirstByRequest_IdAndBloodUnit_IdOrderByIssuedAtDesc(request.getId(), unit.getId())
            .filter(release -> release.getReleaseType() != EmergencyReleaseType.UNCROSSMATCHED)
            .orElse(null);
    }

    /** The pairing passes ABO/Rh, or is the one platelet Rh mismatch a recorded reason permits. */
    private boolean groupStillAllows(PatientBloodGroup screen, BloodUnit unit, TransfusionRequest request,
                                     TransfusionCrossmatch crossmatch) {
        return serologicallyCompatible(screen, unit, request)
            || (crossmatch != null && hasText(crossmatch.getIncompatibilityReason())
                && isPlateletRhOnlyMismatch(screen, unit, request));
    }

    /**
     * The platelet Rh rule turns on whether this recipient is someone a future
     * pregnancy could be affected for (haematologist sign-off 2026-08-25).
     * Derived from the patient record here rather than inside AboGroup, which
     * stays a pure serology rule; UNKNOWN (an unrecognised or missing gender or
     * date of birth) protects.
     */
    private boolean serologicallyCompatible(PatientBloodGroup screen, BloodUnit unit, TransfusionRequest request) {
        Patient recipient = request.getPatient();
        ChildbearingPotential childbearingPotential = recipient == null
            ? ChildbearingPotential.UNKNOWN
            : ChildbearingPotential.of(
                recipient.getGender(), recipient.getDateOfBirth(), LocalDate.now(clock));
        return AboGroup.isCompatible(
            screen.getAboGroup(), screen.getRhFactor(),
            unit.getAboGroup(), unit.getRhFactor(),
            request.getProductType(), childbearingPotential);
    }

    /**
     * A bag held for another request cannot be crossmatched here. A bag issued for THIS request can:
     * a result on it after a repeat screen is what stops it at the bedside. So can one hung for this
     * patient: an emergency bag goes up before its crossmatch, and refusing the result hid it from
     * the clinician. Only a bag hung for some other request stays closed.
     */
    private void requireNotHeldElsewhere(BloodUnit unit, UUID requestId, TransfusionAdministration hung) {
        requireNotHeldByAnother(unit, requestId);
        if (hung == null && unit.getStatus() == BloodUnitStatus.ISSUED
                && administrationRepository.findByBloodUnit_Id(unit.getId()).isPresent()) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.alreadyHung", unit.getUnitNumber());
        }
    }

    /** The administration of this bag for this request, running or ended; null when none. */
    private TransfusionAdministration administrationFor(BloodUnit unit, UUID requestId) {
        return administrationRepository.findByBloodUnit_Id(unit.getId())
            .filter(administration -> administration.getRequest() != null
                && Objects.equals(administration.getRequest().getId(), requestId))
            .orElse(null);
    }

    private static void requireNotHeldByAnother(BloodUnit unit, UUID requestId) {
        boolean held = unit.getStatus() == BloodUnitStatus.ISSUED || unit.getStatus() == BloodUnitStatus.CROSSMATCHED;
        if (held && unit.getRequest() != null && !Objects.equals(unit.getRequest().getId(), requestId)) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.reserved", unit.getUnitNumber());
        }
    }

    /** The bag behind a stopped transfusion or a reaction; a transfused or discarded one stays as it is. */
    private void discardHungUnit(BloodUnit unit, String reason) {
        if (unit != null && unit.getStatus() != BloodUnitStatus.DISCARDED
                && unit.getStatus() != BloodUnitStatus.TRANSFUSED) {
            unit.setStatus(BloodUnitStatus.DISCARDED);
            unit.setDiscardReason(reason);
            unitRepository.save(unit);
        }
    }

    /**
     * QA D858: "has this happened yet" questions. A few minutes of clock skew
     * between the ward PC and the server are tolerated, as the partograph and
     * the postpartum observations do.
     */
    private void requireNotInFuture(LocalDateTime at, String field, String messageKey) {
        if (at != null && at.isAfter(LocalDateTime.now(clock).plus(CLOCK_SKEW))) {
            // QA D901: on its field, so a form can mark it (still a BusinessException).
            throw new FieldBusinessException(field, messageKey);
        }
    }

    /** A screen or a reservation that has already lapsed when recorded protects nobody. */
    private void requireInFuture(LocalDateTime expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now(clock))) {
            throw new com.example.hms.exception.FieldBusinessException("expiresAt", "transfusion.expiryInPast");
        }
    }

    /** "O Rh+", the notation every blood bank reads in any language. */
    private static String groupLabel(AboGroup abo, RhFactor rh) {
        String rhSign = rh == RhFactor.NEGATIVE ? "-" : "+";
        return (abo != null ? abo.name() : "?") + " Rh" + (rh != null ? rhSign : "?");
    }

    /** The product's name in the caller's language (QA D885). */
    private static String productLabel(BloodProductType type) {
        return type == null ? "" : MessageUtil.resolveOrRaw("transfusion.productType." + type.name());
    }

    private void stopInternal(TransfusionAdministration administration, String reason) {
        administration.setStatus(TransfusionAdministrationStatus.STOPPED);
        administration.setCompletedAt(LocalDateTime.now(clock));
        administration.setStopReason(reason);
    }

    /** A request is done once every unit committed to it has been dealt with. */
    private void completeRequestIfDone(TransfusionRequest request) {
        if (request == null || request.isTerminal()) {
            return;
        }
        List<BloodUnit> units = unitRepository.findByRequest_IdOrderByUnitNumberAsc(request.getId());
        boolean anyOutstanding = units.stream().anyMatch(u ->
            u.getStatus() == BloodUnitStatus.CROSSMATCHED || u.getStatus() == BloodUnitStatus.ISSUED);
        if (!units.isEmpty() && !anyOutstanding) {
            request.setStatus(TransfusionRequestStatus.COMPLETED);
            requestRepository.save(request);
        }
    }

    /** A bag's administration with the crossmatch result on file for it, so the panel can show what came back. */
    private TransfusionAdministrationResponseDTO toDetail(TransfusionAdministration administration,
                                                          List<TransfusionReaction> reactions) {
        TransfusionCrossmatch crossmatch = administration.getRequest() == null || administration.getBloodUnit() == null
            ? null
            : crossmatchRepository.findByRequest_IdAndBloodUnit_Id(administration.getRequest().getId(),
                administration.getBloodUnit().getId()).orElse(null);
        return mapper.toDto(administration, reactions, crossmatch);
    }

    private TransfusionRequestResponseDTO toDetail(TransfusionRequest request) {
        return mapper.toDto(request,
            unitRepository.findByRequest_IdOrderByUnitNumberAsc(request.getId()),
            crossmatchRepository.findByRequest_IdOrderByPerformedAtDesc(request.getId()),
            releaseRepository.findByRequest_IdOrderByIssuedAtDesc(request.getId()));
    }

    /** 404-not-403 — a request at another hospital is indistinguishable from a missing one. */
    private TransfusionRequest loadRequestScoped(UUID requestId) {
        TransfusionRequest request = requestRepository.findById(requestId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_REQUEST_NOT_FOUND, requestId));
        requireInScope(request.getHospital(), MSG_REQUEST_NOT_FOUND, requestId);
        return request;
    }

    private BloodUnit loadUnitScoped(UUID unitId) {
        BloodUnit unit = unitRepository.findById(unitId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_UNIT_NOT_FOUND, unitId));
        requireInScope(unit.getHospital(), MSG_UNIT_NOT_FOUND, unitId);
        return unit;
    }

    /**
     * Ending a bag closes its request when it was the last one, so it takes the same locks in the same
     * order as a release, a crossmatch and a cancel: a bag going out meanwhile is seen, and neither
     * side holds what the other waits for. Before anything is read, so what is read is current.
     */
    private void lockAdministrationTargets(UUID administrationId) {
        administrationRepository.findLockTarget(administrationId).ifPresent(target -> {
            requestRepository.lockById(target.getRequestId());
            unitRepository.lockById(target.getUnitId());
        });
    }

    private TransfusionAdministration loadAdministrationScoped(UUID administrationId) {
        TransfusionAdministration administration = administrationRepository.findById(administrationId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ADMIN_NOT_FOUND, administrationId));
        requireInScope(administration.getHospital(), MSG_ADMIN_NOT_FOUND, administrationId);
        return administration;
    }

    private void requireInScope(Hospital hospital, String messageKey, UUID id) {
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope != null && hospital != null && !scope.equals(hospital.getId())) {
            throw new ResourceNotFoundException(messageKey, id);
        }
    }

    /**
     * Blood is physical stock in a building. Every write here needs a real
     * facility, so a super-admin with no active hospital is refused rather than
     * quietly operating unscoped as they do on read-only surfaces.
     */
    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("transfusion.hospitalRequired");
        }
        return hospitalId;
    }

    private Hospital hospitalRef(UUID hospitalId) {
        return hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));
    }

    private Staff currentStaff(UUID hospitalId) {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || hospitalId == null) {
            return null;
        }
        return staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
    }

    private BloodUnitStatus parseUnitStatus(String raw) {
        try {
            return BloodUnitStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("transfusion.unit.statusUnknown", raw);
        }
    }

    private TransfusionRequestStatus parseRequestStatus(String raw) {
        try {
            return TransfusionRequestStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("transfusion.request.statusUnknown", raw);
        }
    }

    /**
     * True when a platelet pairing fails ONLY on the Rh restriction — the ABO
     * half is acceptable under the signed-off platelet rule.
     *
     * <p>Answered by re-running the check with the Rh restriction lifted,
     * rather than by re-deriving groups here, so the single definition of
     * platelet ABO stays in AboGroup and this cannot drift away from it.
     */
    private boolean isPlateletRhOnlyMismatch(PatientBloodGroup group,
                                             BloodUnit unit,
                                             TransfusionRequest transfusionRequest) {
        if (transfusionRequest.getProductType() != BloodProductType.PLATELETS) {
            return false;
        }
        return AboGroup.isCompatible(
            group.getAboGroup(), group.getRhFactor(),
            unit.getAboGroup(), unit.getRhFactor(),
            BloodProductType.PLATELETS, ChildbearingPotential.NO);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
