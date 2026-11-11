package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.DeathDisputeStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.DeathDispute;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.mortality.DeathDisputeOutcomeDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeRequestDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeResponseDTO;
import com.example.hms.repository.DeathDisputeRepository;
import com.example.hms.repository.DeathRecordRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.config.SecurityConstants;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.DeathDisputeService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** See {@link DeathDisputeService}. Every state change takes the death record's lock, then the dispute's. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DeathDisputeServiceImpl implements DeathDisputeService {

    static final String REFERENCE_TYPE = "DEATH_DISPUTE";
    private static final String TITLE_KEY = "mortality.dispute.review.title";
    private static final String BODY_KEY = "mortality.dispute.review.body";
    private static final String ESCALATED_TITLE_KEY = "mortality.dispute.review.escalatedTitle";
    private static final String STATUS_KEY = "mortality.dispute.status";
    private static final String NOT_FOUND_KEY = "mortality.dispute.notFound";
    private static final String AUDIT_ENTITY = "DEATH_DISPUTE";
    private static final String FIELD_REASON = "reason";
    private static final Set<String> ADMIN_ROLE_CODES = Set.of(SecurityConstants.ROLE_HOSPITAL_ADMIN);

    @Value("${hms.mortality.dispute.escalation-hours:4}")
    private long escalationHours = 4;

    private final Clock clock;
    private final DeathDisputeRepository disputeRepository;
    private final DeathRecordRepository deathRecordRepository;
    private final PatientRepository patientRepository;
    private final PatientChartAccess patientChartAccess;
    private final RoleValidator roleValidator;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final InBasketService inBasketService;
    private final AuditEventLogService auditEventLogService;
    private final com.example.hms.service.OrderReviewHoldService orderReview;

    @Override
    public DeathDisputeResponseDTO dispute(UUID patientId, DeathDisputeRequestDTO request) {
        requireStatements(request);
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        UUID actorId = roleValidator.getCurrentUserId();
        // The role at THIS hospital, not the union of the caller's assignments.
        if (hospitalId == null || actorId == null || !roleValidator.isSigningPhysician(actorId, hospitalId)) {
            throw new AccessDeniedException("Only a doctor with an active assignment at this hospital can dispute a death.");
        }
        Patient patient = patientChartAccess.require(patientId, hospitalId);
        // Locked and first read here, so entered in error, or a second dispute, is seen as it was committed.
        DeathRecord record = deathRecordRepository.lockLiveByPatientId(patientId)
            .orElseThrow(() -> new BusinessException("mortality.dispute.notDeceased"));

        DeathDispute open = disputeRepository.findOpen(record.getId(), hospitalId).orElse(null);
        if (open != null) {
            return toDto(open, patient, hospitalId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        DeathDispute dispute = disputeRepository.save(DeathDispute.builder()
            .deathRecordId(record.getId())
            .deathRecordVersion(record.getVersion() != null ? record.getVersion() : 0L)
            .patientId(patientId)
            .hospitalId(hospitalId)
            .disputedById(actorId)
            .disputedByName(currentUsername())
            .disputedAt(now)
            .reason(request.getReason().trim())
            .assessedAlive(true)
            .identityVerified(true)
            .build());

        // In this transaction, against this event and not the death date: what exists now is held for a prescriber.
        // The doctor's reason here reconfirms no order.
        orderReview.snapshot(dispute);

        // In this transaction: a dispute that commits has its review item, and one with nobody to tell is the sweep's.
        if (file(dispute, record.getHospital().getId(), patient, false) > 0) {
            dispute.setReviewFiledAt(now);
        } else {
            log.error("Dispute {} of a death has no hospital administrator to review it; the sweep keeps trying", dispute.getId());
        }
        audit(dispute, hospitalId, actorId, "Death status disputed at hospital " + hospitalId + " by doctor " + actorId
            + ", death record " + record.getId() + " version " + dispute.getDeathRecordVersion()
            + ": assessed alive and identity verified. Care permitted pending review. Reason: " + dispute.getReason());
        return toDto(dispute, patient, hospitalId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeathDisputeResponseDTO> openForReview() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("mortality.hospitalRequired");
        }
        requireDecider(roleValidator.getCurrentUserId(), hospitalId);
        return disputeRepository.findOpenForReview(hospitalId).stream()
            .map(dispute -> toDto(dispute, patientRepository.findByIdUnscoped(dispute.getPatientId()).orElse(null), hospitalId))
            .toList();
    }

    @Override
    public DeathDisputeResponseDTO uphold(UUID disputeId, DeathDisputeOutcomeDTO outcome) {
        String reason = outcome == null || outcome.getReason() == null ? "" : outcome.getReason().trim();
        if (reason.isEmpty()) {
            throw new FieldBusinessException(FIELD_REASON, "mortality.dispute.outcomeReasonRequired");
        }
        DeathDispute dispute = disputeRepository.lockById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, disputeId));
        UUID scope = roleValidator.requireActiveHospitalId();
        UUID actorId = roleValidator.getCurrentUserId();
        UUID recordHospital = deathRecordRepository.findById(dispute.getDeathRecordId())
            .map(record -> record.getHospital().getId()).orElse(null);
        if (scope != null && !scope.equals(dispute.getHospitalId()) && !scope.equals(recordHospital)) {
            throw new ResourceNotFoundException(NOT_FOUND_KEY, disputeId);
        }
        requireDecider(actorId, scope);
        if (!dispute.isOpen()) {
            throw new BusinessException("mortality.dispute.closed");
        }
        close(dispute, DeathDisputeStatus.UPHELD, actorId, currentUsername(), reason, LocalDateTime.now(clock));
        orderReview.disputeClosed(dispute.getId());
        audit(dispute, scope != null ? scope : dispute.getHospitalId(), actorId, "Death upheld: dispute " + dispute.getId() + " closed by " + actorId
            + ", the block on care is back at hospital " + dispute.getHospitalId() + ". Reason: " + reason);
        return toDto(dispute, patientRepository.findByIdUnscoped(dispute.getPatientId()).orElse(null), scope);
    }

    @Override
    public int escalateUnreviewed() {
        LocalDateTime now = LocalDateTime.now(clock);
        int filed = 0;
        LocalDateTime before = now.minusHours(escalationHours);
        for (UUID id : disputeRepository.findDueForEscalation(before)) {
            // Under its lock and first read here: a decision taken since the list was read is seen, and the
            // writes below cannot put a decided dispute back to open.
            DeathDispute dispute = disputeRepository.lockById(id).filter(DeathDispute::isOpen).orElse(null);
            if (dispute == null) {
                continue;
            }
            UUID recordHospital = deathRecordRepository.findById(dispute.getDeathRecordId())
                .map(record -> record.getHospital().getId()).orElse(dispute.getHospitalId());
            boolean escalating = dispute.getReviewFiledAt() != null;
            if (escalating) {
                dispute.setEscalationCount(dispute.getEscalationCount() + 1);
            }
            Patient patient = patientRepository.findByIdUnscoped(dispute.getPatientId()).orElse(null);
            int items = file(dispute, recordHospital, patient, escalating);
            if (items == 0) {
                log.error("Dispute {} of a death still has no hospital administrator to review it", dispute.getId());
                continue;
            }
            if (escalating) {
                dispute.setLastEscalatedAt(now);
            } else {
                dispute.setReviewFiledAt(now);
            }
            filed += items;
        }
        return filed;
    }

    /**
     * The role at the acting hospital: the controller's check reads the union of the caller's assignments. A
     * verified super-admin decides in global view or at the hospital they selected, never at another one.
     */
    private void requireDecider(UUID actorId, UUID scope) {
        if (!com.example.hms.security.CallerIdentity.isSuperAdminFor(scope)
                && (scope == null || !roleValidator.isHospitalAdmin(actorId, scope))) {
            throw new AccessDeniedException("Only a hospital administrator of this hospital can review a dispute.");
        }
    }

    /** Entered in error ends every open dispute of the record. The record's lock is already held by the caller. */
    static void closeAll(List<DeathDispute> disputes, InBasketService inBasket, UUID actorId, String actorName,
                         String reason, LocalDateTime now) {
        for (DeathDispute dispute : disputes) {
            closeRow(dispute, DeathDisputeStatus.ENTERED_IN_ERROR, actorId, actorName, reason, now);
            removeReviewItems(dispute, inBasket);
        }
    }

    private void close(DeathDispute dispute, DeathDisputeStatus outcome, UUID actorId, String actorName, String reason,
                       LocalDateTime now) {
        closeRow(dispute, outcome, actorId, actorName, reason, now);
        removeReviewItems(dispute, inBasketService);
    }

    private static void closeRow(DeathDispute dispute, DeathDisputeStatus outcome, UUID actorId, String actorName,
                                 String reason, LocalDateTime now) {
        dispute.setStatus(outcome);
        dispute.setResolvedAt(now);
        dispute.setResolvedById(actorId);
        dispute.setResolvedByName(actorName);
        dispute.setResolutionReason(reason);
    }

    private static void removeReviewItems(DeathDispute dispute, InBasketService inBasket) {
        for (int level = 0; level <= dispute.getEscalationCount(); level++) {
            inBasket.removeOpenItemsForReference(reference(dispute, level), REFERENCE_TYPE);
        }
    }

    /** The in-basket keeps one item per reference and reader, so each level of escalation has its own reference. */
    private static UUID reference(DeathDispute dispute, int level) {
        return UUID.nameUUIDFromBytes((dispute.getId() + "@" + level).getBytes(StandardCharsets.UTF_8));
    }

    /** One URGENT item for each administrator of the disputing hospital and of the recording one. */
    private int file(DeathDispute dispute, UUID recordHospital, Patient patient, boolean escalating) {
        Map<UUID, UUID> reviewers = new LinkedHashMap<>();
        for (UUID hospital : new java.util.LinkedHashSet<>(java.util.Arrays.asList(dispute.getHospitalId(), recordHospital))) {
            assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospital, ADMIN_ROLE_CODES)
                .forEach(user -> reviewers.putIfAbsent(user.getId(), hospital));
        }
        String titleKey = escalating ? ESCALATED_TITLE_KEY : TITLE_KEY;
        Object[] bodyArgs = {patient != null ? patient.getFullName() : null, dispute.getReason()};
        UUID reference = reference(dispute, dispute.getEscalationCount());
        reviewers.forEach((user, hospital) -> {
            CreateInBasketItemRequest item = new CreateInBasketItemRequest();
            item.setRecipientUserId(user);
            item.setHospitalId(hospital);
            item.setItemType(InBasketItemType.TASK);
            item.setPriority(InBasketPriority.URGENT);
            item.setReferenceId(reference);
            item.setReferenceType(REFERENCE_TYPE);
            item.setPatientId(dispute.getPatientId());
            item.setPatientName(patient != null ? patient.getFullName() : null);
            item.setTitleKey(titleKey);
            item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, titleKey));
            item.setBodyKey(BODY_KEY);
            item.setBodyArgs(bodyArgs);
            item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, BODY_KEY, bodyArgs));
            inBasketService.createItem(item);
        });
        return reviewers.size();
    }

    private static void requireStatements(DeathDisputeRequestDTO request) {
        DeathDisputeRequestDTO body = request != null ? request : new DeathDisputeRequestDTO();
        RequiredFields.check()
            .requireValue(StringUtils.hasText(body.getReason()) ? body.getReason() : null, FIELD_REASON, "mortality.dispute.reasonRequired")
            .requireValue(Boolean.TRUE.equals(body.getAssessedAlive()) ? Boolean.TRUE : null, "assessedAlive",
                "mortality.dispute.assessedAliveRequired")
            .requireValue(Boolean.TRUE.equals(body.getIdentityVerified()) ? Boolean.TRUE : null, "identityVerified",
                "mortality.dispute.identityVerifiedRequired")
            .orThrow();
    }

    /** Ids only: the audit service writes in its own transaction. */
    private void audit(DeathDispute dispute, UUID hospitalId, UUID actorId, String description) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.PATIENT_UPDATE)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY)
                .resourceId(String.valueOf(dispute.getId()))
                .patientId(dispute.getPatientId())
                .hospitalId(hospitalId)
                .userId(actorId)
                .userName(currentUsername())
                .eventDescription(description)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to audit death dispute {}: {}", dispute.getId(), ex.getMessage());
        }
    }

    private DeathDisputeResponseDTO toDto(DeathDispute dispute, Patient patient, UUID readerHospital) {
        UUID recordHospital = deathRecordRepository.findById(dispute.getDeathRecordId())
            .map(record -> record.getHospital().getId()).orElse(null);
        return DeathDisputeResponseDTO.builder()
            .id(dispute.getId())
            .patientId(dispute.getPatientId())
            .patientName(patient != null ? patient.getFullName() : null)
            .patientMrn(patient != null && readerHospital != null ? patient.getMrnForHospital(readerHospital) : null)
            .hospitalId(dispute.getHospitalId())
            .status(dispute.getStatus())
            .reason(dispute.getReason())
            .disputedByName(dispute.getDisputedByName())
            .disputedAt(dispute.getDisputedAt())
            .resolutionReason(dispute.getResolutionReason())
            .resolvedByName(dispute.getResolvedByName())
            .resolvedAt(dispute.getResolvedAt())
            .deathRecordId(Objects.equals(recordHospital, readerHospital) ? dispute.getDeathRecordId() : null)
            .statusMessage(dispute.isOpen() ? MessageUtil.resolve(STATUS_KEY) : null)
            .build();
    }

    private static String currentUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }
}
