package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.QueueClaimExitActor;
import com.example.hms.enums.QueueClaimReleaseReason;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.DispenseMapper;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.PrescriptionQueueClaim;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.WorkQueueClaimDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PrescriptionQueueClaimRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * G13: who is preparing which prescription on the pharmacy work queue.
 *
 * <p><b>Advisory.</b> The claim coordinates people; it guards no data. Only
 * a plain {@link #claim} of a row someone else holds is refused (409), so
 * nobody steals a row silently. Every clinical write succeeds or fails
 * exactly as before, and ends the claim through {@link #releaseOnExit}.
 *
 * <p><b>One lock rule</b> (plan rule 3, G15 rule 1 extended): every path that
 * creates, renews, moves or deletes a claim holds the prescription row lock
 * before it reads the claim row. The endpoints here take it with the
 * hospital in the locking query; the exit paths already hold it.
 *
 * <p><b>Expiry is computed</b>: a claim is active while
 * {@code claimedAt > now - ttl}, with {@code now} from the service clock, at
 * read time and at write time. No sweep, no stored expiry.
 *
 * <p><b>Audits after commit.</b> The audit service is {@code REQUIRES_NEW} and
 * commits at once, so an audit written inside the transaction would survive
 * a rollback (including one at flush) and record a claim change that never
 * happened. Every claim audit is captured as ids and timestamps here and
 * written through {@link TransactionCallbacks#afterCommit}. Descriptions carry
 * ids, timestamps and reason codes only: never a medication, patient or
 * staff name.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrescriptionQueueClaimService {

    static final String HELD_BY_OTHER = "workqueue.claim.heldByOther";
    static final String NOT_HOLDER = "workqueue.claim.notHolder";
    static final String NOT_IN_QUEUE = "workqueue.claim.notInQueue";
    static final String DISABLED = "workqueue.claim.disabled";
    private static final String NOT_FOUND = "prescription.notfound";
    private static final String OPEN_PREPARATION = "dispense.ready.openPreparation";
    private static final String AUDIT_ENTITY = "PRESCRIPTION";
    private static final String PRESCRIPTION_SUFFIX = ", prescription ";

    private final PrescriptionQueueClaimRepository claimRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final DispenseRepository dispenseRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;

    /** {@code pharmacy.work-queue.claim.enabled}; initialised for pure-unit tests. */
    @Value("${pharmacy.work-queue.claim.enabled:true}")
    private boolean enabled = true;

    /** {@code pharmacy.work-queue.claim.ttl}; not stored per row, so a change applies at once. */
    @Value("${pharmacy.work-queue.claim.ttl:PT15M}")
    private Duration ttl = Duration.ofMinutes(15);

    public boolean isEnabled() {
        return enabled;
    }

    public Duration ttl() {
        return ttl;
    }

    /** {@code now - ttl}: a claim stamped after this instant is active. */
    public LocalDateTime activeAfter() {
        return LocalDateTime.now(clock).minus(ttl);
    }

    /**
     * Claim a queue row (AC-1), or renew one's own active claim (AC-4). A row
     * held by someone else answers 409 {@code heldByOther} (AC-3); an expired
     * claim is replaced in place (AC-7).
     */
    @Transactional
    public WorkQueueClaimDTO claim(UUID prescriptionId) {
        return claimOrTakeOver(prescriptionId, false);
    }

    /**
     * Take a colleague's active claim over (AC-6): the same row moves to the
     * caller, audited with the previous holder. With no active claim it
     * behaves as {@link #claim}; on one's own claim it renews.
     */
    @Transactional
    public WorkQueueClaimDTO takeOver(UUID prescriptionId) {
        return claimOrTakeOver(prescriptionId, true);
    }

    /**
     * Release one's own claim (AC-5). Idempotent: nothing to release answers
     * normally. A colleague's active claim answers 409 {@code notHolder}.
     * The status is not checked: ending a claim is always allowed.
     */
    @Transactional
    public void release(UUID prescriptionId) {
        requireEnabled();
        Prescription prescription = lockInScope(prescriptionId);
        UUID callerId = requireCaller();
        UUID rxId = prescription.getId();
        Optional<PrescriptionQueueClaim> existing = claimRepository.findByPrescription_Id(rxId);
        if (existing.isEmpty()) {
            return;
        }
        PrescriptionQueueClaim claim = existing.get();
        LocalDateTime now = LocalDateTime.now(clock);
        UUID holderId = holderIdOf(claim);
        LocalDateTime claimedAt = claim.getClaimedAt();
        if (!isActive(claim, now)) {
            claimRepository.delete(claim);
            auditExpired(rxId, callerId, holderId, claimedAt, now);
            return;
        }
        if (!callerId.equals(holderId)) {
            throw new ConflictException(MessageUtil.resolve(NOT_HOLDER));
        }
        claimRepository.delete(claim);
        auditAfterCommit(callerId, AuditEventType.PRESCRIPTION_QUEUE_CLAIM_RELEASED,
                "Work-queue claim released (" + QueueClaimReleaseReason.RELEASED.name()
                        + "), claimed " + claimedAt + PRESCRIPTION_SUFFIX + rxId,
                rxId);
    }

    /**
     * A write that ends the work on an order ends its claim (plan rule 4).
     * Called inside that write's transaction, after its own validation, by a
     * caller that already holds the prescription row lock and has already
     * authorised the write: so this method makes <b>no</b> scope call (as
     * {@link PreparedFillVoider}). It never refuses anything, and it runs
     * whether or not the feature flag is on, so turning the flag off and on
     * again never brings back a claim whose work has ended.
     *
     * <ul>
     *   <li>No claim: nothing.</li>
     *   <li>Expired: deleted, EXPIRED audited.</li>
     *   <li>Active, and the reason is a prescriber's
     *       ({@link QueueClaimReleaseReason#alwaysRelease()}), or the actor is
     *       the holder, or the actor is not a queue role: RELEASED.</li>
     *   <li>Active, held by someone else, the actor a queue role: TAKEN_OVER.</li>
     * </ul>
     * Every audit is written after commit; a rollback keeps the claim and
     * writes nothing.
     */
    public void releaseOnExit(Prescription prescription,
                              QueueClaimReleaseReason reason,
                              UUID actorUserId,
                              QueueClaimExitActor actorKind) {
        if (prescription == null || prescription.getId() == null) {
            return;
        }
        UUID rxId = prescription.getId();
        Optional<PrescriptionQueueClaim> existing = claimRepository.findByPrescription_Id(rxId);
        if (existing.isEmpty()) {
            return;
        }
        PrescriptionQueueClaim claim = existing.get();
        LocalDateTime now = LocalDateTime.now(clock);
        UUID holderId = holderIdOf(claim);
        LocalDateTime claimedAt = claim.getClaimedAt();
        boolean active = isActive(claim, now);
        claimRepository.delete(claim);

        if (!active) {
            auditExpired(rxId, actorUserId, holderId, claimedAt, now);
            return;
        }
        boolean plainRelease = reason.alwaysRelease()
                || actorKind != QueueClaimExitActor.QUEUE_ROLE
                || (actorUserId != null && actorUserId.equals(holderId));
        if (plainRelease) {
            auditAfterCommit(actorUserId, AuditEventType.PRESCRIPTION_QUEUE_CLAIM_RELEASED,
                    "Work-queue claim of user " + holderId + " (claimed " + claimedAt + ") released ("
                            + reason.name() + ")" + PRESCRIPTION_SUFFIX + rxId,
                    rxId);
        } else {
            auditAfterCommit(actorUserId, AuditEventType.PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER,
                    "Work-queue claim taken over from user " + holderId + " (claimed " + claimedAt
                            + ") by " + reason.name() + PRESCRIPTION_SUFFIX + rxId,
                    rxId);
        }
    }

    /**
     * The active claims of a queue page, keyed by prescription id, in one
     * query (AC-2). Empty when the feature is off.
     */
    @Transactional(readOnly = true)
    public Map<UUID, WorkQueueClaimDTO> activeClaimsFor(List<Prescription> prescriptions) {
        if (!enabled || prescriptions == null || prescriptions.isEmpty()) {
            return Map.of();
        }
        UUID callerId = roleValidator.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now(clock);
        List<UUID> ids = prescriptions.stream().map(Prescription::getId).toList();
        Map<UUID, WorkQueueClaimDTO> active = new HashMap<>();
        for (PrescriptionQueueClaim claim : claimRepository.findByPrescription_IdIn(ids)) {
            if (claim.getPrescription() != null && isActive(claim, now)) {
                UUID rxId = claim.getPrescription().getId();
                active.put(rxId, toDto(rxId, claim, callerId, null));
            }
        }
        return active;
    }

    // ── internals ──

    private WorkQueueClaimDTO claimOrTakeOver(UUID prescriptionId, boolean takeOver) {
        requireEnabled();
        Prescription prescription = lockInScope(prescriptionId);
        // Plan rule 7: the status, then the open preparation, then the claim.
        if (!DispenseServiceImpl.WORK_QUEUE_STATUSES.contains(prescription.getStatus())) {
            throw new ConflictException(MessageUtil.resolve(NOT_IN_QUEUE));
        }
        if (dispenseRepository.existsByPrescription_IdAndStatus(prescription.getId(), DispenseStatus.PENDING)) {
            throw new ConflictException(MessageUtil.resolve(OPEN_PREPARATION));
        }
        UUID callerId = requireCaller();
        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
        LocalDateTime now = LocalDateTime.now(clock);
        UUID rxId = prescription.getId();

        Optional<PrescriptionQueueClaim> existing = claimRepository.findByPrescription_Id(rxId);
        if (existing.isEmpty()) {
            PrescriptionQueueClaim created = claimRepository.save(PrescriptionQueueClaim.builder()
                    .prescription(prescription)
                    .claimedBy(caller)
                    .claimedAt(now)
                    .build());
            auditClaimed(callerId, rxId);
            return toDto(rxId, created, callerId, false);
        }

        PrescriptionQueueClaim claim = existing.get();
        UUID holderId = holderIdOf(claim);
        LocalDateTime previousClaimedAt = claim.getClaimedAt();
        boolean active = isActive(claim, now);

        if (active && callerId.equals(holderId)) {
            // AC-4: a renewal is not a new fact; nothing is audited.
            claim.setClaimedAt(now);
            return toDto(rxId, claimRepository.save(claim), callerId, true);
        }
        if (active && !takeOver) {
            throw new ConflictException(MessageUtil.resolve(HELD_BY_OTHER));
        }

        // Plan rule 5: the row is updated in place, never deleted and
        // re-inserted: Hibernate flushes inserts before deletes, and the
        // unique constraint would refuse the second row.
        claim.setClaimedBy(caller);
        claim.setClaimedAt(now);
        PrescriptionQueueClaim saved = claimRepository.save(claim);
        if (active) {
            auditAfterCommit(callerId, AuditEventType.PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER,
                    "Work-queue claim taken over from user " + holderId + " (claimed " + previousClaimedAt
                            + ")" + PRESCRIPTION_SUFFIX + rxId,
                    rxId);
        } else {
            auditExpired(rxId, callerId, holderId, previousClaimedAt, now);
            auditClaimed(callerId, rxId);
        }
        return toDto(rxId, saved, callerId, false);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResourceNotFoundException(DISABLED);
        }
    }

    /**
     * The scoped lock (AC-14): a null hospital, an unknown id and another
     * hospital's id all answer the same 404, and another tenant's row is
     * never locked, because the hospital is in the locking query.
     */
    private Prescription lockInScope(UUID prescriptionId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null || prescriptionId == null) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        return prescriptionRepository.findByIdAndHospitalIdForUpdate(prescriptionId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    private UUID requireCaller() {
        UUID callerId = roleValidator.getCurrentUserId();
        if (callerId == null) {
            throw new BusinessException("Unable to determine current user");
        }
        return callerId;
    }

    private boolean isActive(PrescriptionQueueClaim claim, LocalDateTime now) {
        return claim.getClaimedAt() != null && claim.getClaimedAt().isAfter(now.minus(ttl));
    }

    /** The holder's id; reading the id does not initialise the LAZY proxy. */
    private static UUID holderIdOf(PrescriptionQueueClaim claim) {
        return claim.getClaimedBy() != null ? claim.getClaimedBy().getId() : null;
    }

    private WorkQueueClaimDTO toDto(UUID prescriptionId, PrescriptionQueueClaim claim, UUID callerId,
                                    Boolean renewed) {
        UUID holderId = holderIdOf(claim);
        LocalDateTime claimedAt = claim.getClaimedAt();
        return WorkQueueClaimDTO.builder()
                .prescriptionId(prescriptionId)
                .claimedByUserId(holderId)
                .claimedByName(DispenseMapper.displayNameOf(claim.getClaimedBy()))
                .claimedAt(claimedAt)
                .expiresAt(claimedAt != null ? claimedAt.plus(ttl) : null)
                .mine(holderId != null && holderId.equals(callerId))
                .renewed(renewed)
                .build();
    }

    private void auditClaimed(UUID actorId, UUID prescriptionId) {
        auditAfterCommit(actorId, AuditEventType.PRESCRIPTION_QUEUE_CLAIMED,
                "Work-queue claim taken" + PRESCRIPTION_SUFFIX + prescriptionId, prescriptionId);
    }

    private void auditExpired(UUID prescriptionId, UUID actorId, UUID holderId, LocalDateTime claimedAt,
                              LocalDateTime now) {
        LocalDateTime expiredAt = claimedAt != null ? claimedAt.plus(ttl) : null;
        auditAfterCommit(actorId, AuditEventType.PRESCRIPTION_QUEUE_CLAIM_EXPIRED,
                "Work-queue claim of user " + holderId + " (claimed " + claimedAt + ") expired at "
                        + expiredAt + ", found " + now + PRESCRIPTION_SUFFIX + prescriptionId,
                prescriptionId);
    }

    /**
     * Captured now, written after commit through the REQUIRES_NEW audit
     * service; only plain values cross into the callback. Best effort: a
     * failed audit is logged (its type only) and swallowed.
     */
    private void auditAfterCommit(UUID actorId, AuditEventType type, String description, UUID prescriptionId) {
        String resourceId = prescriptionId.toString();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                        .userId(actorId)
                        .eventType(type)
                        .eventDescription(description)
                        .status(AuditStatus.SUCCESS)
                        .resourceId(resourceId)
                        .entityType(AUDIT_ENTITY)
                        .build());
            } catch (RuntimeException e) {
                log.warn("Failed to log audit event {} for prescription {}: {}",
                        type, resourceId, e.getClass().getSimpleName());
            }
        });
    }
}
