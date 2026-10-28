package com.example.hms.service.impl;

import com.example.hms.enums.AboGroup;
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
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TransfusionAdministrationRepository;
import com.example.hms.repository.TransfusionCrossmatchRepository;
import com.example.hms.repository.TransfusionReactionRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.service.TransfusionService;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
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

        bloodGroupRepository
            .findByPatient_IdAndHospital_IdAndSupersededFalse(patient.getId(), hospitalId)
            .ifPresent(current -> {
                // A blood group does not change. A disagreement means one of the
                // two results is wrong, and quietly letting the newer one win is
                // how the wrong blood reaches a patient — so it must be declared.
                boolean groupChanged = current.getAboGroup() != request.getAboGroup()
                    || current.getRhFactor() != request.getRhFactor();
                if (groupChanged && !StringUtils.hasText(request.getCorrectionReason())) {
                    throw new BusinessException("transfusion.bloodGroup.changeNeedsReason",
                        groupLabel(current.getAboGroup(), current.getRhFactor()),
                        groupLabel(request.getAboGroup(), request.getRhFactor()));
                }
                current.setSuperseded(Boolean.TRUE);
                bloodGroupRepository.save(current);
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
            .build();

        return mapper.toDto(bloodGroupRepository.save(group));
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
                throw new BusinessException("transfusion.unit.duplicate", request.getUnitNumber());
            });
        LocalDate today = LocalDate.now(clock);
        if (request.getExpiresOn() != null && !request.getExpiresOn().isAfter(today)) {
            throw new BusinessException("transfusion.unit.expiredOnReceipt", request.getUnitNumber());
        }
        // QA D883: a bag collected in 2027 was received into stock. A donation
        // date after today, or after the bag's own expiry, is a typing error.
        if (request.getCollectedOn() != null && request.getCollectedOn().isAfter(today)) {
            throw new BusinessException("transfusion.unit.collectedInFuture");
        }
        if (request.getCollectedOn() != null && request.getExpiresOn() != null
                && request.getCollectedOn().isAfter(request.getExpiresOn())) {
            throw new BusinessException("transfusion.unit.collectedAfterExpiry");
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
            throw new BusinessException("transfusion.unit.discardReasonRequired");
        }
        BloodUnit unit = loadUnitScoped(unitId);
        if (unit.getStatus() == BloodUnitStatus.TRANSFUSED) {
            throw new BusinessException("transfusion.unit.alreadyTransfused", unit.getUnitNumber());
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
            throw new BusinessException("transfusion.request.noTypeAndScreen");
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
            throw new BusinessException("transfusion.request.cancelReasonRequired");
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
        // Release anything held for this patient rather than stranding it.
        for (BloodUnit unit : unitRepository.findByRequest_IdOrderByUnitNumberAsc(requestId)) {
            if (unit.getStatus() == BloodUnitStatus.CROSSMATCHED || unit.getStatus() == BloodUnitStatus.ISSUED) {
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
            throw new BusinessException("transfusion.unit.expired", unit.getUnitNumber(), unit.getExpiresOn());
        }
        if (unit.isTerminal()) {
            throw new BusinessException("transfusion.unit.noLongerUsable", unit.getUnitNumber());
        }
        if (unit.getProductType() != transfusionRequest.getProductType()) {
            throw new BusinessException("transfusion.unit.productMismatch", unit.getUnitNumber(),
                productLabel(unit.getProductType()), productLabel(transfusionRequest.getProductType()));
        }
        requireInFuture(request.getExpiresAt());

        PatientBloodGroup group = transfusionRequest.getBloodGroup();
        if (group == null) {
            throw new BusinessException("transfusion.crossmatch.noTypeAndScreen");
        }
        if (!group.screenIsCurrent(LocalDateTime.now(clock))) {
            throw new BusinessException("transfusion.crossmatch.screenLapsed");
        }

        // The platelet Rh rule turns on whether this recipient is someone a
        // future pregnancy could be affected for (haematologist sign-off
        // 2026-08-25). Derived from the patient record here rather than
        // inside AboGroup, which stays a pure serology rule; UNKNOWN — an
        // unrecognised or missing gender or date of birth — protects.
        Patient recipient = transfusionRequest.getPatient();
        ChildbearingPotential childbearingPotential = recipient == null
            ? ChildbearingPotential.UNKNOWN
            : ChildbearingPotential.of(
                recipient.getGender(), recipient.getDateOfBirth(), LocalDate.now(clock));

        boolean serologicallyCompatible = AboGroup.isCompatible(
            group.getAboGroup(), group.getRhFactor(),
            unit.getAboGroup(), unit.getRhFactor(),
            transfusionRequest.getProductType(), childbearingPotential);

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
                throw new BusinessException(
                    rhOnly ? "transfusion.crossmatch.incompatibleRhOverridable" : "transfusion.crossmatch.incompatible",
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
            unit.setStatus(BloodUnitStatus.CROSSMATCHED);
            unit.setRequest(transfusionRequest);
            unitRepository.save(unit);
            if (transfusionRequest.getStatus() == TransfusionRequestStatus.REQUESTED) {
                transfusionRequest.setStatus(TransfusionRequestStatus.CROSSMATCHED);
                requestRepository.save(transfusionRequest);
            }
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
            throw new BusinessException("transfusion.unit.expired", unit.getUnitNumber(), unit.getExpiresOn());
        }

        TransfusionCrossmatch crossmatch = crossmatchRepository
            .findByRequest_IdAndBloodUnit_Id(requestId, unitId).orElse(null);

        if (crossmatch == null || !crossmatch.isUsableAt(LocalDateTime.now(clock))) {
            // Emergency release: group O Rh-negative may go out uncrossmatched,
            // because in a haemorrhage the crossmatch takes longer than the
            // patient has. Anything else must be crossmatched first.
            boolean emergencyRelease = request.isEmergency()
                && unit.getAboGroup() == AboGroup.emergencyReleaseGroup()
                && unit.getRhFactor() == AboGroup.emergencyReleaseRh();
            if (!emergencyRelease) {
                throw issueRefusal(unit, crossmatch);
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

        TransfusionRequest transfusionRequest = loadRequestScoped(request.getRequestId());
        UUID hospitalId = transfusionRequest.getHospital().getId();
        BloodUnit unit = loadUnitScoped(request.getBloodUnitId());

        if (unit.getStatus() != BloodUnitStatus.ISSUED) {
            throw new BusinessException("transfusion.unit.notIssued", unit.getUnitNumber());
        }
        if (unit.isExpiredOn(LocalDate.now(clock))) {
            throw new BusinessException("transfusion.unit.expired", unit.getUnitNumber(), unit.getExpiresOn());
        }
        administrationRepository.findByBloodUnit_Id(unit.getId()).ifPresent(existing -> {
            throw new BusinessException("transfusion.unit.alreadyHung", unit.getUnitNumber());
        });

        Staff administering = currentStaff(hospitalId);
        if (administering == null) {
            throw new AccessDeniedException(
                "Only a clinician with a staff profile at this hospital can transfuse.");
        }
        Staff verifier = staffRepository.findById(request.getVerifiedByStaffId())
            // QA D503 class: the second checker is an active staff member AT this hospital
            // (a record with no hospital is not one), and a different person from the caller.
            .filter(s -> s.getHospital() != null && Objects.equals(s.getHospital().getId(), hospitalId))
            .filter(Staff::isActive)
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", request.getVerifiedByStaffId()));
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
            throw new BusinessException("transfusion.administration.stopReasonRequired");
        }
        TransfusionAdministration administration = loadAdministrationScoped(administrationId);
        if (!administration.isInProgress()) {
            throw new BusinessException("transfusion.administration.closed");
        }
        stopInternal(administration, reason);
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

        // The implicated bag is evidence for the workup, not stock.
        BloodUnit unit = administration.getBloodUnit();
        if (unit != null && unit.getStatus() != BloodUnitStatus.DISCARDED) {
            unit.setStatus(BloodUnitStatus.DISCARDED);
            unit.setDiscardReason("Transfusion reaction (" + request.getReactionType() + ")");
            unitRepository.save(unit);
        }

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
            throw new BusinessException("transfusion.expiryInPast");
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
