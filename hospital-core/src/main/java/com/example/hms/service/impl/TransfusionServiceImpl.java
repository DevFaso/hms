package com.example.hms.service.impl;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.BloodProductType;
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
import com.example.hms.payload.dto.transfusion.PatientBloodGroupRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionReactionRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionReactionResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestResponseDTO;
import com.example.hms.repository.BloodUnitRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientBloodGroupRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TransfusionAdministrationRepository;
import com.example.hms.repository.TransfusionCrossmatchRepository;
import com.example.hms.repository.TransfusionReactionRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.service.TransfusionService;
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
import java.util.List;
import java.util.Objects;
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
                + "group O Rh-negative is the only compatible release", saved.getId());
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
        TransfusionRequest transfusionRequest = loadRequestScoped(requestId);
        if (transfusionRequest.isTerminal()) {
            throw new BusinessException("transfusion.request.closed");
        }

        BloodUnit unit = loadUnitScoped(request.getBloodUnitId());
        if (unit.isExpiredOn(LocalDate.now(clock))) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.expired", unit.getUnitNumber(),
                DisplayDates.date(unit.getExpiresOn()));
        }
        if (unit.isTerminal()) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.noLongerUsable", unit.getUnitNumber());
        }
        if (unit.getProductType() != transfusionRequest.getProductType()) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.productMismatch", unit.getUnitNumber(),
                productLabel(unit.getProductType()), productLabel(transfusionRequest.getProductType()));
        }
        requireNotHeldElsewhere(unit, requestId);
        requireInFuture(request.getExpiresAt());

        // QA CX-011: the patient's current screen. A request raised before a
        // repeat must neither stay blocked by the screen it replaced, nor be
        // crossmatched against a group the repeat corrected.
        PatientBloodGroup group = currentScreen(transfusionRequest);
        if (group == null) {
            throw new BusinessException("transfusion.crossmatch.noTypeAndScreen");
        }
        if (!group.screenIsCurrent(LocalDateTime.now(clock))) {
            throw new BusinessException("transfusion.crossmatch.screenLapsed");
        }

        boolean serologicallyCompatible = serologicallyCompatible(group, unit, transfusionRequest);

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
        TransfusionCrossmatch crossmatch = crossmatchRepository
            .findByRequest_IdAndBloodUnit_Id(requestId, unit.getId())
            .orElseGet(() -> TransfusionCrossmatch.builder()
                .request(transfusionRequest)
                .bloodUnit(unit)
                .hospital(transfusionRequest.getHospital())
                .build());

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
        } else if (unit.getStatus() == BloodUnitStatus.CROSSMATCHED || unit.getStatus() == BloodUnitStatus.ISSUED) {
            // A bag reserved here, or out and not hung, and now found incompatible goes
            // back to the lab. Left as it was, nothing could ever release it: issue and
            // hang refuse it, and it is hidden from the units that can be crossmatched.
            unit.setStatus(BloodUnitStatus.RETURNED);
            unitRepository.save(unit);
        }
        return mapper.toDto(saved);
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
        TransfusionRequest request = loadRequestScoped(requestId);
        if (request.isTerminal()) {
            throw new BusinessException("transfusion.request.closed");
        }
        BloodUnit unit = loadUnitScoped(unitId);
        if (unit.isExpiredOn(LocalDate.now(clock))) {
            throw new BusinessException("transfusion.unit.expired", unit.getUnitNumber(),
                DisplayDates.date(unit.getExpiresOn()));
        }
        // A bag that was transfused, discarded or retired cannot go out again,
        // whatever crossmatch it still has on file.
        if (unit.isTerminal()) {
            throw new BusinessException("transfusion.unit.noLongerUsable", unit.getUnitNumber());
        }
        // The crossmatch path has always checked the product; the emergency path never reached it.
        if (unit.getProductType() != request.getProductType()) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.productMismatch", unit.getUnitNumber(),
                productLabel(unit.getProductType()), productLabel(request.getProductType()));
        }
        // Emergency release takes no crossmatch, so nothing else stops it taking a bag
        // held for another patient, or one already running into them.
        requireNotHeldByAnother(unit, requestId);
        if (administrationRepository.findByBloodUnit_Id(unitId).isPresent()) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.alreadyHung", unit.getUnitNumber());
        }

        TransfusionCrossmatch crossmatch = crossmatchRepository
            .findByRequest_IdAndBloodUnit_Id(requestId, unitId).orElse(null);

        LocalDateTime now = LocalDateTime.now(clock);
        BusinessException refusal = crossmatch == null || !crossmatch.isUsableAt(now)
            ? issueRefusal(unit, crossmatch)
            : crossmatchBasisRefusal(request, unit, crossmatch, now);
        if (refusal != null) {
            // Emergency release: group O Rh-negative may go out uncrossmatched,
            // because in a haemorrhage the crossmatch takes longer than the
            // patient has. Anything else must be crossmatched first.
            if (!emergencyReleaseAllowed(request, unit, crossmatch)) {
                throw refusal;
            }
            log.warn("EMERGENCY uncrossmatched release of unit {} against request {}",
                unit.getUnitNumber(), requestId);
        }

        unit.setStatus(BloodUnitStatus.ISSUED);
        unit.setRequest(request);
        BloodUnit saved = unitRepository.save(unit);

        if (request.getStatus() != TransfusionRequestStatus.ISSUED) {
            request.setStatus(TransfusionRequestStatus.ISSUED);
            requestRepository.save(request);
        }
        return mapper.toDto(saved);
    }

    // ── Bedside ─────────────────────────────────────────────────────────

    @Override
    public TransfusionAdministrationResponseDTO startAdministration(
        TransfusionAdministrationRequestDTO request) {

        if (request.getVerifiedByStaffId() == null && request.getVerifiedByUserId() == null) {
            throw new FieldBusinessException("verifiedByStaffId", "transfusion.administration.verifierRequired");
        }
        TransfusionRequest transfusionRequest = loadRequestScoped(request.getRequestId());
        UUID hospitalId = transfusionRequest.getHospital().getId();
        BloodUnit unit = loadUnitScoped(request.getBloodUnitId());

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
        return mapper.toDto(saved, List.of());
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
        return mapper.toDto(administrationRepository.save(administration),
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
        return mapper.toDto(administrationRepository.save(administration),
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
            .map(a -> mapper.toDto(a, reactionRepository.findByAdministration_IdOrderByOnsetAtDesc(a.getId())))
            .toList();
    }

    @Override
    public TransfusionReactionResponseDTO recordReaction(UUID administrationId,
                                                         TransfusionReactionRequestDTO request) {
        // QA D858: NURSE_A recorded a reaction with onsetAt = now + 24h and got
        // 201. A reaction is observed, so its onset is in the past (a few
        // minutes of clock skew between the ward PC and the server aside).
        requireNotInFuture(request.getOnsetAt(), "onsetAt", "transfusion.reaction.onsetInFuture");
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
     * O Rh-negative is the universal RED CELL donor. It is not a universal donor of
     * plasma or platelets, so a pairing the ABO/Rh rules reject for a known group does
     * not go out on urgency. O negative red cells pass every group, so this blocks no
     * resuscitation. Nor does urgency overrule what was found: an incompatible result
     * for this bag, or a crossmatch older than antibodies found since, stops it here
     * at the lab, not at the bedside.
     */
    private boolean emergencyReleaseAllowed(TransfusionRequest request, BloodUnit unit,
                                            TransfusionCrossmatch crossmatch) {
        if (!request.isEmergency()
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
     * no crossmatch to stand on and passes: nothing here may block a resuscitation.
     */
    private void requireCrossmatchStillStands(TransfusionRequest request, BloodUnit unit) {
        TransfusionCrossmatch crossmatch = crossmatchRepository
            .findByRequest_IdAndBloodUnit_Id(request.getId(), unit.getId()).orElse(null);
        if (crossmatch == null) {
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
     * A bag held for another request cannot be crossmatched here, and one already
     * hung has nothing left to decide. A bag issued for THIS request can: a result
     * on it after a repeat screen is what stops it at the bedside.
     */
    private void requireNotHeldElsewhere(BloodUnit unit, UUID requestId) {
        requireNotHeldByAnother(unit, requestId);
        if (unit.getStatus() == BloodUnitStatus.ISSUED
                && administrationRepository.findByBloodUnit_Id(unit.getId()).isPresent()) {
            throw new FieldBusinessException(FIELD_BLOOD_UNIT_ID, "transfusion.unit.alreadyHung", unit.getUnitNumber());
        }
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

    private TransfusionRequestResponseDTO toDetail(TransfusionRequest request) {
        return mapper.toDto(request,
            unitRepository.findByRequest_IdOrderByUnitNumberAsc(request.getId()),
            crossmatchRepository.findByRequest_IdOrderByPerformedAtDesc(request.getId()));
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
