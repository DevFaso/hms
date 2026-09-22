package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabResultComparisonDTO;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultSignatureRequestDTO;
import com.example.hms.payload.dto.LabResultTrendPointDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.ElapsedTime;
import com.example.hms.utility.RoleValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.LabReflexRule;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.repository.LabReflexRuleRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.service.lab.LabOrderLifecycle;

@Service
@RequiredArgsConstructor
public class LabResultServiceImpl implements LabResultService {
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ROLE_LAB_DIRECTOR = "ROLE_LAB_DIRECTOR";
    private static final String UNKNOWN_CLINICIAN = "Unknown clinician";
    private static final String CRITICAL_FLAG = "CRITICAL";


    private static final String LAB_RESULT_NOT_FOUND = "labresult.notfound";
    private static final String LAB_ORDER_NOT_FOUND = "laborder.notfound";

    private static final Logger LOG = LoggerFactory.getLogger(LabResultServiceImpl.class);

    private final LabResultRepository labResultRepository;
    private final com.example.hms.service.lab.LabResultEntryGuard labResultEntryGuard;
    private final LabOrderRepository labOrderRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final LabResultMapper labResultMapper;
    private final RoleValidator roleValidator;
    private final AuthService authService;
    private final UserRepository userRepository;
    private final InstrumentOutboxService instrumentOutboxService;
    private final LabReflexRuleRepository labReflexRuleRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final CriticalValueNotificationService criticalValueNotificationService;

    /**
     * Whether a normal-range result is released the moment it is saved, with
     * nobody attesting to it.
     *
     * <p><b>Default false, and that is deliberate (B5).</b> Release is the
     * laboratory saying "this number is correct and you may act on it"
     * ({@link com.example.hms.service.lab.LabResultAuthority#RELEASE_ROLES}
     * narrows who may say so). Auto-verification used to say it for every
     * non-abnormal result instantly, under the name "Autoverification", which
     * made the release gate decorative for the bulk of results. A hospital
     * whose analysers and delta checks justify auto-release turns this on with
     * {@code hms.lab.auto-verification.enabled=true}; until then a human
     * releases.
     */
    @Value("${hms.lab.auto-verification.enabled:false}")
    private boolean autoVerificationEnabled;

    @Override
    @Transactional
    public LabResultResponseDTO createLabResult(LabResultRequestDTO request, Locale locale) {
        // Loaded under the same write lock the release path takes: the
        // reopen/advance decision below reads the status, and an unlocked
        // read could see RESULTED while a concurrent release of the last
        // result is committing COMPLETED — this insert would then neither
        // reopen nor wait, leaving an unreleased result on a completed
        // order. Locking first makes this transaction wait for that commit
        // and see COMPLETED. (A foreign tenant holds the lock only for the
        // instant before the 404 below.)
        LabOrder labOrder = labOrderRepository.findWithLockById(request.getLabOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(LAB_ORDER_NOT_FOUND));
        // Same 404-not-403 tenancy comparison as every other single-row path
        // here (B11): a foreign tenant must not learn the order exists, let
        // alone attach a result to it.
        requireOrderInActiveHospital(labOrder);

        Hospital hospital = extractHospitalFromLabOrder(labOrder);

        // Who may record THIS test's result. Role alone cannot answer it: a
        // nurse recording a bedside glucose is doing their job, and the same
        // nurse typing in a chemistry panel is not. The gate reads the test's
        // point-of-care flag, which is why it lives here rather than in the
        // controller annotation — the annotation runs before the order and
        // its test are loaded.
        labResultEntryGuard.requireMayEnterResult(labOrder.getLabTestDefinition());

    UUID currentUserId = authService.getCurrentUserId();
    validateLabResultAuthor(currentUserId, hospital.getId());

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(request.getAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));

        LabResult result = labResultMapper.toEntity(request, labOrder, assignment);
        LabResult saved = labResultRepository.save(result);

        // An entered result IS the order's RESULTED state (B2). Nothing else
        // advanced the order, so released results never reached the ordering
        // doctor's review queue, which keys on COMPLETED. A result landing on
        // a COMPLETED order (a correction, a late analyte) re-opens it first:
        // the doctor must see the order as having something new to review.
        if (LabOrderLifecycle.reopenForResult(labOrder)) {
            labOrderRepository.save(labOrder);
        }
        advanceOrder(labOrder, LabOrderStatus.RESULTED);
        // One severity for both decisions below. The REST path never sets
        // abnormalFlag (no DTO field; only MLLP populates it), so gating
        // auto-release on the flag alone released critical manual results.
        String severity = severityOf(saved);
        performAutoVerification(saved, severity);
        if (saved.isReleased()) {
            completeOrderIfAllReleased(labOrder);
        }
        triggerReflexOrders(saved);
        instrumentOutboxService.enqueueResultObservation(saved);
        // P0 #5 — critical values must reach the ordering provider; the
        // service swallows its own failures so the result write never rolls back.
        criticalValueNotificationService.notifyIfCritical(saved, severity);

        return labResultMapper.toResponseDTO(saved);
    }

    /**
     * The mapper's reference-range verdict (NORMAL / LOW / HIGH / UNSPECIFIED),
     * or null when it cannot map.
     *
     * <p>The mapper answers UNSPECIFIED for a test definition it finds
     * uninitialised, and the order's definition is LAZY: on a laboratory
     * role's entry nothing has touched it yet at this point (the entry guard
     * reads it only for bedside roles), so without the explicit initialise a
     * potassium of 50 read as "unspecified" here and as HIGH in the response.
     */
    private String severityOf(LabResult result) {
        if (result.getLabOrder() != null && result.getLabOrder().getLabTestDefinition() != null) {
            org.hibernate.Hibernate.initialize(result.getLabOrder().getLabTestDefinition());
        }
        LabResultResponseDTO dto = labResultMapper.toResponseDTO(result);
        return dto != null ? dto.getSeverityFlag() : null;
    }

    private void advanceOrder(LabOrder labOrder, LabOrderStatus target) {
        if (LabOrderLifecycle.advance(labOrder, target)) {
            labOrderRepository.save(labOrder);
        }
    }

    /**
     * The order is COMPLETED once every one of its results is released — the
     * point at which the ordering doctor's review queue picks it up.
     *
     * <p>"Every result" means every result that exists when the release
     * lands. A LabOrder names exactly one {@code LabTestDefinition}
     * ({@code @ManyToOne}), so the common case is one result per order;
     * reflex tests are separate child orders. A result that arrives later
     * (a correction, an extra analyte) re-opens the order to RESULTED in
     * {@code createLabResult}, so completing early is never final.
     *
     * <p>The order row is locked ({@code PESSIMISTIC_WRITE}) before the
     * results are counted: under READ COMMITTED two concurrent releases of
     * the last two results each saw the other as unreleased, so neither
     * completed the order and nothing ever re-evaluated. With the lock the
     * second release waits for the first to commit and sees it.
     */
    private void completeOrderIfAllReleased(LabOrder labOrder) {
        if (labOrder == null || labOrder.getId() == null) {
            return;
        }
        LabOrder locked = labOrderRepository.findWithLockById(labOrder.getId()).orElse(labOrder);
        List<LabResult> results = labResultRepository.findByLabOrder_Id(locked.getId());
        if (!results.isEmpty() && results.stream().allMatch(LabResult::isReleased)) {
            advanceOrder(locked, LabOrderStatus.COMPLETED);
        }
    }

    private void requireOrderInActiveHospital(LabOrder labOrder) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && labOrder.getHospital() != null
                && !activeHospitalId.equals(labOrder.getHospital().getId())) {
            throw new ResourceNotFoundException(LAB_ORDER_NOT_FOUND);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public LabResultResponseDTO getLabResultById(UUID id, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && labResult.getLabOrder() != null
                && labResult.getLabOrder().getHospital() != null
                && !activeHospitalId.equals(labResult.getLabOrder().getHospital().getId())) {
            throw new ResourceNotFoundException(LAB_RESULT_NOT_FOUND);
        }

        LabResultResponseDTO response = labResultMapper.toResponseDTO(labResult);
        response.setTrendHistory(buildTrendHistory(labResult));
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getAllLabResults(Locale locale) {
        UUID currentUserId = authService.getCurrentUserId();

        if (authService.hasRole(ROLE_SUPER_ADMIN)) {
            return labResultRepository.findAll().stream()
                .map(labResultMapper::toResponseDTO)
                .toList();
        }

        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser_IdAndActiveTrue(currentUserId);
        if (assignments.isEmpty()) {
            return List.of();
        }

        Set<UUID> hospitalIds = assignments.stream()
            .map(UserRoleHospitalAssignment::getHospital)
            .filter(Objects::nonNull)
            .map(Hospital::getId)
            .collect(Collectors.toSet());

        if (hospitalIds.isEmpty()) {
            return List.of();
        }

        return labResultRepository.findByLabOrder_Hospital_IdIn(hospitalIds).stream()
            .map(labResultMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LabResultResponseDTO> getLabResultsPage(Pageable pageable, Locale locale) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // Super-admin: return all paged
            return labResultRepository.findAll(pageable)
                .map(labResultMapper::toResponseDTO);
        }
        return labResultRepository.findByLabOrder_Hospital_IdIn(Set.of(hospitalId), pageable)
            .map(labResultMapper::toResponseDTO);
    }

    @Override
    @Transactional
    public LabResultResponseDTO updateLabResult(UUID id, LabResultRequestDTO request, Locale locale) {
    LabResult labResult = labResultRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        // Hospital scope enforcement
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && labResult.getLabOrder() != null
                && labResult.getLabOrder().getHospital() != null
                && !activeHospitalId.equals(labResult.getLabOrder().getHospital().getId())) {
            throw new ResourceNotFoundException(LAB_RESULT_NOT_FOUND);
        }

        // Amending a result is entering one. Same gate — otherwise a bedside
        // role blocked from creating a lab-performed result could simply
        // overwrite an existing one instead.
        labResultEntryGuard.requireMayEnterResult(
            labResult.getLabOrder() != null ? labResult.getLabOrder().getLabTestDefinition() : null);

        LabOrder labOrder = labOrderRepository.findById(request.getLabOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(LAB_ORDER_NOT_FOUND));

        Hospital hospital = extractHospitalFromLabOrder(labOrder);
    UUID currentUserId = authService.getCurrentUserId();
    validateLabResultAuthor(currentUserId, hospital.getId());

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(request.getAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));

        labResult.setLabOrder(labOrder);
        labResult.setResultValue(request.getResultValue());
        labResult.setResultUnit(request.getResultUnit());
        labResult.setResultDate(request.getResultDate());
        labResult.setNotes(request.getNotes());
        labResult.setAssignment(assignment);

        LabResult updated = labResultRepository.save(labResult);

        return labResultMapper.toResponseDTO(updated);
    }

    @Override
    public void deleteLabResult(UUID id, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && labResult.getLabOrder() != null
                && labResult.getLabOrder().getHospital() != null
                && !activeHospitalId.equals(labResult.getLabOrder().getHospital().getId())) {
            throw new ResourceNotFoundException(LAB_RESULT_NOT_FOUND);
        }
        labResultRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void acknowledgeLabResult(UUID id, Locale locale) {
        UUID currentUserId = authService.getCurrentUserId();
        // Unknown ids used to be silently swallowed to accommodate the
        // synthetic pending-review rows; those are gone, so a missing
        // result is a real 404 again.
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));
        // Tenant guard (the getLabResultById idiom, 404-not-403): this was
        // the one write path with no scope check — a foreign tenant could
        // acknowledge, and thereby silence, another hospital's critical
        // result by guessing the UUID.
        requireResultInActiveHospital(labResult);
        acknowledgeResult(labResult, currentUserId);
    }

    @Override
    @Transactional
    public LabResultResponseDTO recordCriticalReadBack(
            UUID id,
            com.example.hms.payload.dto.CriticalValueReadBackRequestDTO request,
            Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));
        // Tenant guard: worse than acknowledge — the response DTO carries
        // patient name + value, so this was a cross-tenant PHI read as
        // well as a write, and a matching read-back removes the row from
        // the escalation sweep permanently.
        requireResultInActiveHospital(labResult);

        UUID actorId = authService.getCurrentUserId();
        LabResult updated = criticalValueNotificationService.recordReadBack(
            labResult, request.getRepeatedValue(), actorId, resolveActorDisplay(actorId));
        return labResultMapper.toResponseDTO(updated);
    }

    /**
     * The shared 404-not-403 tenancy comparison used by every other
     * single-row path in this class (get/update/delete/compare). LabResult
     * has no hospital column of its own — scope flows through
     * labOrder.hospital. Null active scope = super-admin, unscoped.
     */
    private void requireResultInActiveHospital(LabResult labResult) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && labResult.getLabOrder() != null
                && labResult.getLabOrder().getHospital() != null
                && !activeHospitalId.equals(labResult.getLabOrder().getHospital().getId())) {
            throw new ResourceNotFoundException(LAB_RESULT_NOT_FOUND);
        }
    }

    /**
     * Best-effort human name for the read-back record. A missing display name
     * must not block a clinician confirming a critical value.
     */
    private String resolveActorDisplay(UUID actorId) {
        if (actorId == null) {
            return null;
        }
        try {
            return userRepository.findById(actorId)
                .map(u -> {
                    String first = u.getFirstName() == null ? "" : u.getFirstName();
                    String last = u.getLastName() == null ? "" : u.getLastName();
                    String full = (first + " " + last).trim();
                    return full.isEmpty() ? u.getUsername() : full;
                })
                .orElse(null);
        } catch (RuntimeException ex) {
            LOG.warn("Could not resolve display name for read-back actor {}: {}", actorId, ex.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public LabResultResponseDTO releaseLabResult(UUID id, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));
        // Release was the one write path here without the scope comparison
        // (B11); it runs before the role check so a foreign tenant — including
        // a super-admin pinned to another hospital — sees 404, not 403.
        requireResultInActiveHospital(labResult);

        Hospital hospital = extractHospitalFromLabOrder(labResult.getLabOrder());
        UUID hospitalId = hospital != null ? hospital.getId() : null;
        UUID actorId = authService.getCurrentUserId();

        validateReleasePermissions(actorId, hospitalId);

        if (labResult.isReleased()) {
            return labResultMapper.toResponseDTO(labResult);
        }

        labResult.setReleased(true);
        labResult.setReleasedAt(LocalDateTime.now());
        labResult.setReleasedByUserId(actorId);
        labResult.setReleasedByDisplay(resolveActorDisplay(actorId, hospitalId));

        labResultRepository.save(labResult);
        completeOrderIfAllReleased(labResult.getLabOrder());
        return labResultMapper.toResponseDTO(labResult);
    }

    @Override
    @Transactional
    public LabResultResponseDTO signLabResult(UUID id, LabResultSignatureRequestDTO request, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        Hospital hospital = extractHospitalFromLabOrder(labResult.getLabOrder());
        UUID hospitalId = hospital != null ? hospital.getId() : null;
        UUID actorId = authService.getCurrentUserId();

        validateSignPermissions(actorId, hospitalId);

        String actorDisplay = resolveActorDisplay(actorId, hospitalId);
        LocalDateTime now = LocalDateTime.now();

        labResult.setSignedAt(now);
        labResult.setSignedByUserId(actorId);
        labResult.setSignedByDisplay(actorDisplay);
        labResult.setSignatureValue(normalizeSignatureValue(request));
        labResult.setSignatureNotes(normalizeSignatureNotes(request));

        // Signing is the LAB attesting its own result; acknowledging is the
        // ORDERING CLINICIAN confirming receipt. Conflating them is mostly a
        // harmless convenience — except on a critical result, where the
        // auto-acknowledge would silence the escalation sweep with no read-back
        // ever recorded, bypassing the guard on the acknowledge path. A signed
        // critical result therefore stays unacknowledged (and the sweep keeps
        // chasing) until someone reads the value back.
        if (labResult.getCriticalNotifiedAt() == null || labResult.getCriticalReadBackAt() != null) {
            labResult.setAcknowledged(true);
            labResult.setAcknowledgedAt(now);
            labResult.setAcknowledgedByUserId(actorId);
            labResult.setAcknowledgedByDisplay(actorDisplay);
        }

        labResultRepository.save(labResult);
        return labResultMapper.toResponseDTO(labResult);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getLabResultsByLabOrderId(UUID labOrderId, Locale locale) {
    return labResultRepository.findByLabOrder_Id(labOrderId).stream()
        .map(labResultMapper::toResponseDTO)
        .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getLabResultsByPatientId(UUID patientId, Locale locale) {
    return labResultRepository.findByLabOrder_Patient_Id(patientId).stream()
        .map(labResultMapper::toResponseDTO)
        .toList();
    }

    private Hospital extractHospitalFromLabOrder(LabOrder labOrder) {
        if (labOrder.getHospital() != null) {
            return labOrder.getHospital();
        }

        if (labOrder.getEncounter() != null && labOrder.getEncounter().getHospital() != null) {
            return labOrder.getEncounter().getHospital();
        } else if (labOrder.getPatient().getPrimaryHospital() != null) {
            return labOrder.getPatient().getPrimaryHospital();
        } else {
            throw new ResourceNotFoundException("hospital.notfound");
        }
    }

    private void validateReleasePermissions(UUID userId, UUID hospitalId) {
        if (userId == null) {
            throw new BusinessException("Unable to determine current user for release operation.");
        }
        if (authService.hasRole(ROLE_SUPER_ADMIN)) {
            return;
        }
        if (hospitalId == null) {
            throw new BusinessException("Unable to determine hospital context for lab result release.");
        }

        // LabResultAuthority.RELEASE_ROLES, checked against the caller's
        // assignment at THIS hospital (B10). This list used to admit doctors,
        // nurses, midwives and hospital admins — exactly the roles the
        // annotation had already shut out, so a doctor holding a lab role at
        // some other hospital could still release here.
        boolean allowed = roleValidator.isLabScientist(userId, hospitalId)
            || roleValidator.isLabManager(userId, hospitalId)
            || roleValidator.hasRole(userId, hospitalId, ROLE_LAB_DIRECTOR);

        if (!allowed) {
            throw new BusinessException("Only laboratory scientists, managers or directors can release lab results.");
        }
    }

    private void validateSignPermissions(UUID userId, UUID hospitalId) {
        if (userId == null) {
            throw new BusinessException("Unable to determine current user for sign operation.");
        }
        if (authService.hasRole(ROLE_SUPER_ADMIN)) {
            return;
        }
        if (hospitalId == null) {
            throw new BusinessException("Unable to determine hospital context for lab result sign-off.");
        }

        boolean allowed = roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isMidwife(userId, hospitalId)
            || roleValidator.isLabScientist(userId, hospitalId);

        if (!allowed) {
            throw new BusinessException("Only attending clinicians may sign lab results.");
        }
    }

    private String resolveActorDisplay(UUID userId, UUID hospitalId) {
        if (userId == null) {
            return UNKNOWN_CLINICIAN;
        }

        if (hospitalId != null) {
            var assignment = assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId);
            if (assignment.isPresent()) {
                return formatUserDisplay(assignment.get().getUser());
            }
        }

        return assignmentRepository.findFirstByUserIdAndActiveTrue(userId)
            .map(UserRoleHospitalAssignment::getUser)
            .map(this::formatUserDisplay)
            .or(() -> userRepository.findById(userId).map(this::formatUserDisplay))
            .orElse(UNKNOWN_CLINICIAN);
    }

    private String formatUserDisplay(User user) {
        if (user == null) {
            return UNKNOWN_CLINICIAN;
        }
        String fullName = (nullToEmpty(user.getFirstName()) + " " + nullToEmpty(user.getLastName())).trim();
        if (StringUtils.hasText(fullName)) {
            return fullName;
        }
        if (StringUtils.hasText(user.getEmail())) {
            return user.getEmail();
        }
        if (StringUtils.hasText(user.getUsername())) {
            return user.getUsername();
        }
        return UNKNOWN_CLINICIAN;
    }

    private String normalizeSignatureValue(LabResultSignatureRequestDTO request) {
        if (request == null) {
            return null;
        }
        String signature = request.getSignature();
        return StringUtils.hasText(signature) ? signature.trim() : null;
    }

    private String normalizeSignatureNotes(LabResultSignatureRequestDTO request) {
        if (request == null) {
            return null;
        }
        String notes = request.getNotes();
        return StringUtils.hasText(notes) ? notes.trim() : null;
    }

    // ── MVP3 helpers ─────────────────────────────────────────────────────────

    /**
     * Auto-release only a result that is normal by BOTH signals: the HL7
     * abnormal flag (set by MLLP inbound) and the mapper's reference-range
     * severity (the only signal a manually entered result has). LOW, HIGH
     * and CRITICAL stay unreleased for a human; UNSPECIFIED (no reference
     * range on the test) counts as "nothing abnormal found".
     */
    private void performAutoVerification(LabResult result, String severity) {
        if (!autoVerificationEnabled) {
            return;
        }
        boolean severityNormal = severity == null
            || "NORMAL".equalsIgnoreCase(severity)
            || LabResultMapper.FLAG_UNSPECIFIED.equalsIgnoreCase(severity);
        if (!result.isReleased()
                && severityNormal
                && (result.getAbnormalFlag() == null
                    || result.getAbnormalFlag() == AbnormalFlag.NORMAL)) {
            result.setReleased(true);
            result.setReleasedAt(LocalDateTime.now());
            result.setReleasedByDisplay("Autoverification");
            labResultRepository.save(result);
            LOG.debug("Auto-verified result {}", result.getId());
        }
    }

    private void triggerReflexOrders(LabResult result) {
        LabOrder parent = result.getLabOrder();
        if (parent == null || parent.getLabTestDefinition() == null) return;
        UUID testDefId = parent.getLabTestDefinition().getId();
        List<LabReflexRule> rules = labReflexRuleRepository
            .findByTriggerTestDefinition_IdAndActiveTrue(testDefId);
        for (LabReflexRule rule : rules) {
            if (evaluateReflexCondition(rule.getCondition(), result)) {
                createReflexChildOrder(rule, parent, result);
            }
        }
    }

    private boolean evaluateReflexCondition(String conditionJson, LabResult result) {
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> cond =
                new tools.jackson.databind.ObjectMapper()
                    .readValue(conditionJson, java.util.Map.class);
            if (cond.containsKey("severityFlag")) {
                String required = (String) cond.get("severityFlag");
                String actual = result.getAbnormalFlag() != null
                    ? result.getAbnormalFlag().name() : "NORMAL";
                return required.equalsIgnoreCase(actual);
            }
            if (cond.containsKey("thresholdValue") && cond.containsKey("thresholdOperator")) {
                double threshold = ((Number) cond.get("thresholdValue")).doubleValue();
                String operator = (String) cond.get("thresholdOperator");
                double value = Double.parseDouble(result.getResultValue().trim());
                return switch (operator.toUpperCase()) {
                    case "GT"  -> value > threshold;
                    case "GTE" -> value >= threshold;
                    case "LT"  -> value < threshold;
                    case "LTE" -> value <= threshold;
                    default    -> false;
                };
            }
        } catch (Exception e) {
            LOG.warn("Failed to evaluate reflex condition '{}' for result {}: {}",
                conditionJson, result.getId(), e.getMessage());
        }
        return false;
    }

    private void createReflexChildOrder(LabReflexRule rule, LabOrder parent, LabResult result) {
        LabTestDefinition reflexDef = labTestDefinitionRepository
            .findById(rule.getReflexTestDefinition().getId()).orElse(null);
        if (reflexDef == null) {
            LOG.warn("Reflex rule {} references unknown test definition {}",
                rule.getId(), rule.getReflexTestDefinition().getId());
            return;
        }
        LabOrder child = LabOrder.builder()
            .patient(parent.getPatient())
            .orderingStaff(parent.getOrderingStaff())
            .encounter(parent.getEncounter())
            .labTestDefinition(reflexDef)
            .hospital(parent.getHospital())
            .assignment(parent.getAssignment())
            .orderDatetime(LocalDateTime.now())
            .status(LabOrderStatus.ORDERED)
            .priority(parent.getPriority())
            .clinicalIndication("Reflex from order: " + parent.getId())
            .medicalNecessityNote("Auto-generated reflex order triggered by result " + result.getId())
            .orderChannel(parent.getOrderChannel())
            .orderChannelOther(parent.getOrderChannelOther())
            // A reflex order is the parent order continued (B12): the same
            // medical necessity, the same provider, the same documentation.
            // createLabOrder mandates these; the child used to carry none.
            // NOT copied: providerSignatureDigest / signedAt / signedByUserId.
            // The provider attested to the PARENT test; copying their
            // e-signature onto a test the rule ordered would fabricate an
            // attestation. The entity persists without one (only the REST
            // create path demands a signature), so the child records
            // truthfully that no provider signed it.
            .primaryDiagnosisCode(parent.getPrimaryDiagnosisCode())
            .additionalDiagnosisCodes(new ArrayList<>(parent.getAdditionalDiagnosisCodes() != null
                ? parent.getAdditionalDiagnosisCodes() : List.of()))
            .orderingProviderNpi(parent.getOrderingProviderNpi())
            .documentationSharedWithLab(parent.isDocumentationSharedWithLab())
            .documentationReference(parent.getDocumentationReference())
            .build();
        labOrderRepository.save(child);
        LOG.info("Created reflex child order {} (test: {}) triggered by result {}",
            child.getId(), reflexDef.getTestCode(), result.getId());
    }

    private void validateLabResultAuthor(UUID userId, UUID hospitalId) {
        boolean allowed = roleValidator.hasRole(userId, hospitalId, "ROLE_LAB_SCIENTIST")
            || roleValidator.isMidwife(userId, hospitalId)
            || roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isNurse(userId, hospitalId)
            || roleValidator.isLabTechnician(userId, hospitalId)
            || roleValidator.isLabManager(userId, hospitalId)
            || roleValidator.hasRole(userId, hospitalId, ROLE_LAB_DIRECTOR)
            || roleValidator.hasRole(userId, hospitalId, "ROLE_QUALITY_MANAGER");
        if (!allowed) {
            throw new BusinessException("User does not have a lab or clinical role for this hospital.");
        }
    }

    private void acknowledgeResult(LabResult result, UUID userId) {
        if (result.isAcknowledged()) {
            LOG.debug("Lab result {} already acknowledged", result.getId());
            return;
        }
        // A critical result cannot be acknowledged without a read-back. Until
        // the 2026-08-21 reassessment this path silenced the escalation sweep
        // (which exits on acknowledged=false) with no read-back ever recorded —
        // the whole ceremony was optional for exactly the results it exists
        // for. The read-back path sets acknowledged itself on a match, so this
        // guard never blocks it.
        if (result.getCriticalNotifiedAt() != null && result.getCriticalReadBackAt() == null) {
            throw new BusinessException(
                "This is a critical result: acknowledge it by reading the value back, not by "
                    + "dismissing the alert. Use the read-back action.");
        }
        result.setAcknowledged(true);
        result.setAcknowledgedAt(LocalDateTime.now());
        result.setAcknowledgedByUserId(userId);
        result.setAcknowledgedByDisplay(resolveDisplayName(result));
        labResultRepository.save(result);
    }

    private List<LabResultTrendPointDTO> buildTrendHistory(LabResult source) {
        LabOrder labOrder = source.getLabOrder();
        if (labOrder == null
            || labOrder.getPatient() == null
            || labOrder.getPatient().getId() == null
            || labOrder.getLabTestDefinition() == null
            || labOrder.getLabTestDefinition().getId() == null) {
            return List.of();
        }

        UUID patientId = labOrder.getPatient().getId();
        UUID testDefinitionId = labOrder.getLabTestDefinition().getId();

        List<LabResult> rawTrend = labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(patientId, testDefinitionId);

        if (rawTrend.isEmpty()) {
            return List.of();
        }

        return rawTrend.stream()
            .map(labResultMapper::toTrendPointDTO)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(LabResultTrendPointDTO::getResultDate, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    private String resolveDisplayName(LabResult result) {
        if (result.getAssignment() != null && result.getAssignment().getUser() != null) {
            var user = result.getAssignment().getUser();
            String fullName = (nullToEmpty(user.getFirstName()) + " " + nullToEmpty(user.getLastName())).trim();
            if (!fullName.isEmpty()) {
                return fullName;
            }
            if (user.getEmail() != null) {
                return user.getEmail();
            }
        }
        return UNKNOWN_CLINICIAN;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ==================== Enhanced Trending Methods (Story #5) ====================

    @Override
    @Transactional(readOnly = true)
    public LabResultComparisonDTO compareLabResults(UUID currentResultId, Locale locale) {
        LabResult current = labResultRepository.findById(currentResultId)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        // Hospital scope enforcement
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && current.getLabOrder() != null
                && current.getLabOrder().getHospital() != null
                && !activeHospitalId.equals(current.getLabOrder().getHospital().getId())) {
            throw new ResourceNotFoundException(LAB_RESULT_NOT_FOUND);
        }

        LabOrder labOrder = current.getLabOrder();
        if (labOrder == null || labOrder.getPatient() == null || labOrder.getLabTestDefinition() == null) {
            throw new BusinessException("Cannot compare lab results: missing patient or test definition");
        }

        UUID patientId = labOrder.getPatient().getId();
        UUID testDefinitionId = labOrder.getLabTestDefinition().getId();

        List<LabResult> trendResults = labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(patientId, testDefinitionId);

        LabResult previous = trendResults.stream()
            .filter(r -> r.getResultDate().isBefore(current.getResultDate()))
            .findFirst()
            .orElse(null);

        List<LabResultTrendPointDTO> trendHistory = trendResults.stream()
            .map(labResultMapper::toTrendPointDTO)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(LabResultTrendPointDTO::getResultDate, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        LabResultTrendPointDTO currentPoint = labResultMapper.toTrendPointDTO(current);
        LabResultTrendPointDTO previousPoint = previous != null ? labResultMapper.toTrendPointDTO(previous) : null;

        LabResultComparisonDTO.ComparisonMetadata comparison = calculateComparison(current, previous, trendHistory);

        return LabResultComparisonDTO.builder()
            .testCode(labOrder.getLabTestDefinition().getTestCode())
            .testName(labOrder.getLabTestDefinition().getName())
            .patientId(patientId.toString())
            .patientName(labOrder.getPatient().getFullName())
            .currentResult(currentPoint)
            .previousResult(previousPoint)
            .comparison(comparison)
            .trendHistory(trendHistory)
            .referenceRanges(extractReferenceRanges(labOrder))
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultComparisonDTO> compareSequentialResults(UUID patientId, UUID testDefinitionId, Locale locale) {
        List<LabResult> allResults = labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(patientId, testDefinitionId);

        if (allResults.isEmpty()) {
            return List.of();
        }

        List<LabResultComparisonDTO> comparisons = new ArrayList<>();
        for (int i = 0; i < allResults.size(); i++) {
            LabResult current = allResults.get(i);
            LabResult previous = (i < allResults.size() - 1) ? allResults.get(i + 1) : null;

            LabResultTrendPointDTO currentPoint = labResultMapper.toTrendPointDTO(current);
            LabResultTrendPointDTO previousPoint = previous != null ? labResultMapper.toTrendPointDTO(previous) : null;

            LabResultComparisonDTO.ComparisonMetadata comparison = calculateComparison(current, previous, buildTrendHistory(current));

            comparisons.add(LabResultComparisonDTO.builder()
                .testCode(current.getLabOrder().getLabTestDefinition().getTestCode())
                .testName(current.getLabOrder().getLabTestDefinition().getName())
                .patientId(patientId.toString())
                .patientName(current.getLabOrder().getPatient().getFullName())
                .currentResult(currentPoint)
                .previousResult(previousPoint)
                .comparison(comparison)
                .referenceRanges(extractReferenceRanges(current.getLabOrder()))
                .build());
        }

        return comparisons;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getCriticalResults(UUID hospitalId, LocalDateTime since, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<LabResult> results = labResultRepository.findByLabOrder_Hospital_IdIn(List.of(effectiveHospitalId));

        return results.stream()
            .filter(r -> r.getResultDate() != null && r.getResultDate().isAfter(since))
            .map(labResultMapper::toResponseDTO)
            .filter(dto -> CRITICAL_FLAG.equalsIgnoreCase(dto.getSeverityFlag()) || "HIGH".equalsIgnoreCase(dto.getSeverityFlag()))
            .sorted(Comparator.comparing(LabResultResponseDTO::getResultDate, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getCriticalResultsRequiringAcknowledgment(UUID hospitalId, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<LabResult> results = labResultRepository.findByLabOrder_Hospital_IdIn(List.of(effectiveHospitalId));

        return results.stream()
            .filter(r -> !r.isAcknowledged())
            .map(labResultMapper::toResponseDTO)
            .filter(dto -> CRITICAL_FLAG.equalsIgnoreCase(dto.getSeverityFlag()) || "HIGH".equalsIgnoreCase(dto.getSeverityFlag()))
            .sorted(Comparator.comparing(LabResultResponseDTO::getResultDate, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
    }

    private LabResultComparisonDTO.ComparisonMetadata calculateComparison(LabResult current, LabResult previous, List<LabResultTrendPointDTO> trendHistory) {
        if (previous == null) {
            return LabResultComparisonDTO.ComparisonMetadata.builder()
                .trendDirection(LabResultComparisonDTO.TrendDirection.INSUFFICIENT_DATA)
                .significanceLevel("BASELINE")
                .interpretation("First recorded measurement - no comparison available")
                .build();
        }

        String currentVal = current.getResultValue();
        String previousVal = previous.getResultValue();
        long daysBetween = ElapsedTime.daysBetween(previous.getResultDate(), current.getResultDate());

        // Attempt numeric comparison
        try {
            double currentNum = Double.parseDouble(currentVal);
            double previousNum = Double.parseDouble(previousVal);
            double absoluteChange = currentNum - previousNum;
            double percentageChange = ((currentNum - previousNum) / previousNum) * 100;

            LabResultComparisonDTO.TrendDirection direction = determineTrendDirection(trendHistory);
            String significance = determineSignificance(percentageChange);
            String interpretation = generateInterpretation(currentNum, previousNum, absoluteChange, percentageChange, daysBetween, current.getResultUnit());

            return LabResultComparisonDTO.ComparisonMetadata.builder()
                .absoluteChange(String.format("%.2f %s", absoluteChange, current.getResultUnit() != null ? current.getResultUnit() : ""))
                .percentageChange(percentageChange)
                .trendDirection(direction)
                .daysBetween(daysBetween)
                .significanceLevel(significance)
                .crossedThreshold(false) // Future: Implement reference range boundary checking
                .interpretation(interpretation)
                .build();
        } catch (NumberFormatException e) {
            // Non-numeric comparison
            boolean changed = !currentVal.equalsIgnoreCase(previousVal);
            return LabResultComparisonDTO.ComparisonMetadata.builder()
                .absoluteChange(changed ? "Changed from " + previousVal + " to " + currentVal : "No change")
                .trendDirection(changed ? LabResultComparisonDTO.TrendDirection.FLUCTUATING : LabResultComparisonDTO.TrendDirection.STABLE)
                .daysBetween(daysBetween)
                .significanceLevel(changed ? "SIGNIFICANT" : "STABLE")
                .interpretation(changed ? "Qualitative change detected" : "Result unchanged")
                .build();
        }
    }

    private LabResultComparisonDTO.TrendDirection determineTrendDirection(List<LabResultTrendPointDTO> trendHistory) {
        if (trendHistory.size() < 2) {
            return LabResultComparisonDTO.TrendDirection.INSUFFICIENT_DATA;
        }

        List<Double> numericValues = trendHistory.stream()
            .map(point -> {
                try {
                    return Double.parseDouble(point.getResultValue());
                } catch (NumberFormatException e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .toList();

        if (numericValues.size() < 2) {
            return LabResultComparisonDTO.TrendDirection.INSUFFICIENT_DATA;
        }

        // Simple trend analysis: compare first half to second half
        int midpoint = numericValues.size() / 2;
        double firstHalfAvg = numericValues.subList(0, midpoint).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double secondHalfAvg = numericValues.subList(midpoint, numericValues.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double changePercent = Math.abs((secondHalfAvg - firstHalfAvg) / firstHalfAvg) * 100;

        if (changePercent < 5) {
            return LabResultComparisonDTO.TrendDirection.STABLE;
        } else if (secondHalfAvg > firstHalfAvg) {
            return LabResultComparisonDTO.TrendDirection.INCREASING;
        } else {
            return LabResultComparisonDTO.TrendDirection.DECREASING;
        }
    }

    private String determineSignificance(double percentageChange) {
        double absPercent = Math.abs(percentageChange);

        if (absPercent > 50) {
            return CRITICAL_FLAG;
        } else if (absPercent > 25) {
            return "SIGNIFICANT";
        } else if (absPercent > 10) {
            return "MINOR";
        } else {
            return "STABLE";
        }
    }

    private String generateInterpretation(double current, double previous, double absoluteChange, double percentageChange, long daysBetween, String unit) {
        String direction = absoluteChange > 0 ? "increased" : "decreased";
        String unitStr = unit != null ? " " + unit : "";

        return String.format("Value %s by %.2f%s (%.1f%%) over %d days. Previous: %.2f%s, Current: %.2f%s",
            direction, Math.abs(absoluteChange), unitStr, Math.abs(percentageChange), daysBetween,
            previous, unitStr, current, unitStr);
    }

    private List<LabResultReferenceRangeDTO> extractReferenceRanges(LabOrder labOrder) {
        if (labOrder == null || labOrder.getLabTestDefinition() == null) {
            return List.of();
        }

        return labOrder.getLabTestDefinition().getReferenceRanges().stream()
            .map(range -> LabResultReferenceRangeDTO.builder()
                .minValue(range.getMinValue())
                .maxValue(range.getMaxValue())
                .unit(range.getUnit())
                .ageMin(range.getAgeMin())
                .ageMax(range.getAgeMax())
                .gender(range.getGender())
                .notes(range.getNotes())
                .build())
            .toList();
    }
}
