package com.example.hms.service.impl;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.ChildbearingPotential;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.TransfusionAdministrationStatus;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.BusinessException;
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
                    throw new BusinessException(
                        ("This patient's recorded group is %s %s and this result is %s %s. A blood "
                            + "group does not change: state a correction reason if the previous "
                            + "result was wrong.")
                            .formatted(current.getAboGroup(), current.getRhFactor(),
                                request.getAboGroup(), request.getRhFactor()));
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
                throw new BusinessException(
                    "Unit %s is already on record here. Two bags carrying one number is a "
                        .formatted(request.getUnitNumber())
                        + "wrong-unit incident waiting to happen.");
            });
        if (request.getExpiresOn() != null && !request.getExpiresOn().isAfter(LocalDate.now(clock))) {
            throw new BusinessException("Unit %s is already expired and cannot be received into stock."
                .formatted(request.getUnitNumber()));
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
            throw new BusinessException("A reason is required to discard a blood unit.");
        }
        BloodUnit unit = loadUnitScoped(unitId);
        if (unit.getStatus() == BloodUnitStatus.TRANSFUSED) {
            throw new BusinessException("Unit %s has already been transfused and cannot be discarded."
                .formatted(unit.getUnitNumber()));
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
            throw new BusinessException(
                "This patient has no type and screen on record. Record one, or raise the request "
                    + "as EMERGENCY if there is no time to type them.");
        }

        Encounter encounter = null;
        if (request.getEncounterId() != null) {
            encounter = encounterRepository.findById(request.getEncounterId())
                .filter(e -> e.getHospital() == null
                    || Objects.equals(e.getHospital().getId(), hospitalId))
                .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound", request.getEncounterId()));
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
            throw new BusinessException("A reason is required to cancel a transfusion request.");
        }
        TransfusionRequest request = loadRequestScoped(requestId);
        if (request.isTerminal()) {
            throw new BusinessException("This request is already %s.".formatted(request.getStatus()));
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
            throw new BusinessException("This request is %s and cannot be crossmatched against."
                .formatted(transfusionRequest.getStatus()));
        }

        BloodUnit unit = loadUnitScoped(request.getBloodUnitId());
        if (unit.isExpiredOn(LocalDate.now(clock))) {
            throw new BusinessException("Unit %s expired on %s and cannot be crossmatched."
                .formatted(unit.getUnitNumber(), unit.getExpiresOn()));
        }
        if (unit.isTerminal()) {
            throw new BusinessException("Unit %s is %s and is no longer usable."
                .formatted(unit.getUnitNumber(), unit.getStatus()));
        }
        if (unit.getProductType() != transfusionRequest.getProductType()) {
            throw new BusinessException("Unit %s is %s but the request is for %s."
                .formatted(unit.getUnitNumber(), unit.getProductType(), transfusionRequest.getProductType()));
        }

        PatientBloodGroup group = transfusionRequest.getBloodGroup();
        if (group == null) {
            throw new BusinessException(
                "This patient has no type and screen on record, so no unit can be crossmatched. "
                    + "Emergency release issues group O Rh-negative uncrossmatched instead.");
        }
        if (!group.screenIsCurrent(LocalDateTime.now(clock))) {
            throw new BusinessException(
                "The antibody screen for this patient has lapsed or was never performed. Repeat the "
                    + "screen before crossmatching — a patient transfused or pregnant since the last "
                    + "one can have developed new antibodies.");
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
                    ("Unit %s is %s %s and this patient is %s %s: that pairing is ABO/Rh incompatible "
                        + "for %s and cannot be recorded as compatible.%s")
                        .formatted(unit.getUnitNumber(), unit.getAboGroup(), unit.getRhFactor(),
                            group.getAboGroup(), group.getRhFactor(),
                            transfusionRequest.getProductType(),
                            rhOnly
                                ? " Give a reason to override the platelet Rh restriction."
                                : ""));
            }
            log.warn("Platelet Rh restriction overridden for request {} with unit {}: {}",
                transfusionRequest.getId(), unit.getUnitNumber(), request.getIncompatibilityReason());
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
            throw new BusinessException("This request is %s.".formatted(request.getStatus()));
        }
        BloodUnit unit = loadUnitScoped(unitId);
        if (unit.isExpiredOn(LocalDate.now(clock))) {
            throw new BusinessException("Unit %s expired on %s and cannot be issued."
                .formatted(unit.getUnitNumber(), unit.getExpiresOn()));
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
                throw new BusinessException(issueRefusal(unit, crossmatch));
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
            throw new BusinessException(
                "Unit %s is %s. Only an issued unit can be hung.".formatted(unit.getUnitNumber(), unit.getStatus()));
        }
        if (unit.isExpiredOn(LocalDate.now(clock))) {
            throw new BusinessException("Unit %s expired on %s and must not be transfused."
                .formatted(unit.getUnitNumber(), unit.getExpiresOn()));
        }
        administrationRepository.findByBloodUnit_Id(unit.getId()).ifPresent(existing -> {
            throw new BusinessException("Unit %s has already been hung.".formatted(unit.getUnitNumber()));
        });

        Staff administering = currentStaff(hospitalId);
        if (administering == null) {
            throw new AccessDeniedException(
                "Only a clinician with a staff profile at this hospital can transfuse.");
        }
        Staff verifier = staffRepository.findById(request.getVerifiedByStaffId())
            .filter(s -> s.getHospital() == null || Objects.equals(s.getHospital().getId(), hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", request.getVerifiedByStaffId()));

        // The independent second check. A transfusion is the one administration
        // where a single signature is not accepted practice anywhere.
        if (Objects.equals(administering.getId(), verifier.getId())) {
            throw new BusinessException(
                "The bedside check requires two people. Name the second clinician who independently "
                    + "verified this unit against this patient.");
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
        TransfusionAdministration administration = loadAdministrationScoped(administrationId);
        if (!administration.isInProgress()) {
            throw new BusinessException("This transfusion is already %s.".formatted(administration.getStatus()));
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
            throw new BusinessException("A reason is required to stop a transfusion.");
        }
        TransfusionAdministration administration = loadAdministrationScoped(administrationId);
        if (!administration.isInProgress()) {
            throw new BusinessException("This transfusion is already %s.".formatted(administration.getStatus()));
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
        TransfusionAdministration administration = loadAdministrationScoped(administrationId);

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
    private String issueRefusal(BloodUnit unit, TransfusionCrossmatch crossmatch) {
        if (crossmatch == null) {
            return ("Unit %s has not been crossmatched against this request. Only group O "
                + "Rh-negative may be issued uncrossmatched, and only on an EMERGENCY request.")
                .formatted(unit.getUnitNumber());
        }
        if (Boolean.TRUE.equals(crossmatch.getCompatible())) {
            return "The crossmatch for unit %s has expired. Repeat it before issuing."
                .formatted(unit.getUnitNumber());
        }
        return "The crossmatch for unit %s came back incompatible. It must not be issued."
            .formatted(unit.getUnitNumber());
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
            throw new BusinessException(
                "An active hospital is required: blood units and transfusions belong to a facility.");
        }
        return hospitalId;
    }

    private Hospital hospitalRef(UUID hospitalId) {
        return hospitalRepository.findById(hospitalId)
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
            throw new BusinessException("Unknown blood unit status: %s".formatted(raw));
        }
    }

    private TransfusionRequestStatus parseRequestStatus(String raw) {
        try {
            return TransfusionRequestStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unknown transfusion request status: %s".formatted(raw));
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
