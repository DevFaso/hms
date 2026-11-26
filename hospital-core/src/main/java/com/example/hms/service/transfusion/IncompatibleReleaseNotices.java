package com.example.hms.service.transfusion;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.IncompatibleAntibodyStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.IncompatibleReleaseDecision;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.IncompatibleReleaseDecisionRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.RoleExpansion;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.support.IncompatibleReleaseAuthority;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What a release despite an incompatible crossmatch owes its readers (V381, rule set
 * INCOMPATIBLE_RELEASE_AUTHORITY): on the fallback, the alert and the countersignature task of the laboratory
 * director and the physicians; on every release, the monitoring task and the haemovigilance prompt; and the
 * escalation of a countersignature past its deadline.
 *
 * <p><b>Nothing here runs in the transaction of a release</b>, as for an O positive release
 * ({@link OPositiveReleaseNotices}). The release writes its decision row and commits; the row is the obligation
 * ({@code notices_filed_at} is null). {@link #deliver} then resolves the recipients and files the items in a
 * transaction of its own, which also marks the row: a failure rolls that transaction back, the row stays owed,
 * and the sweep ({@code OPositiveCountersignEscalationScheduler}) delivers it again. A release is never held or
 * undone by a notice, and a notice is never lost to a failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.INCOMPATIBLE_RELEASE_AUTHORITY)
public class IncompatibleReleaseNotices {

    public static final String COUNTERSIGN_TITLE_KEY = "transfusion.incompatible.countersign.inBasketTitle";
    public static final String COUNTERSIGN_BODY_KEY = "transfusion.incompatible.countersign.inBasketBody";
    public static final String MONITORING_TITLE_KEY = "transfusion.incompatible.monitoring.inBasketTitle";
    public static final String MONITORING_BODY_KEY = "transfusion.incompatible.monitoring.inBasketBody";
    public static final String HAEMOVIGILANCE_TITLE_KEY = "transfusion.incompatible.haemovigilance.inBasketTitle";
    public static final String HAEMOVIGILANCE_BODY_KEY = "transfusion.incompatible.haemovigilance.inBasketBody";
    public static final String HAEMOVIGILANCE_NO_ANTIBODY_BODY_KEY = "transfusion.incompatible.haemovigilance.noAntibody.inBasketBody";
    public static final String OVERDUE_TITLE_KEY = "transfusion.incompatible.overdue.inBasketTitle";
    public static final String OVERDUE_BODY_KEY = "transfusion.incompatible.overdue.inBasketBody";
    public static final String COUNTERSIGN_REFERENCE_TYPE = "TRANSFUSION_INCOMPATIBLE_COUNTERSIGN";
    public static final String MONITORING_REFERENCE_TYPE = "TRANSFUSION_INCOMPATIBLE_MONITORING";
    public static final String HAEMOVIGILANCE_REFERENCE_TYPE = "TRANSFUSION_INCOMPATIBLE_HAEMOVIGILANCE";
    public static final String OVERDUE_REFERENCE_TYPE = "TRANSFUSION_INCOMPATIBLE_COUNTERSIGN_OVERDUE";
    private static final String DECISION_ENTITY_TYPE = "INCOMPATIBLE_RELEASE_DECISION";

    /** The laboratory director: alerted at once on a fallback release, and told of a countersignature past its deadline. */
    public static final Set<String> DIRECTOR_ROLE_CODES = Set.of(SecurityConstants.ROLE_LAB_DIRECTOR);

    /** Who watches the patient afterwards and may record the monitoring: whoever hangs a unit. */
    public static final Set<String> MONITOR_ROLE_CODES = Set.of(
        SecurityConstants.ROLE_DOCTOR, RoleExpansion.ROLE_PHYSICIAN, SecurityConstants.ROLE_SURGEON,
        SecurityConstants.ROLE_MIDWIFE, SecurityConstants.ROLE_NURSE);

    private final IncompatibleReleaseDecisionRepository incompatibleRows;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final OnCallScheduleRepository onCallRepository;
    private final InBasketService inBasketService;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;

    /** The releases whose notices are still owed, the oldest first. */
    public List<UUID> pendingIds() {
        return incompatibleRows.findPendingNoticeIds();
    }

    /**
     * Files the notices of one release and marks the row, in one transaction of its own. On the fallback: a
     * CRITICAL task to countersign for the laboratory directors, the clinician who asked for the blood and the
     * physicians on call, who are the people the countersignature accepts (with none of them, every physician and
     * laboratory director of the hospital); never the person who issued the bag. On every release: the monitoring
     * task for the clinicians of the request, and the haemovigilance prompt for them and the laboratory directors.
     * What is already done is not asked again. When a notice has nobody who can act on it, what can be filed is
     * filed and the row stays owed, loudly.
     *
     * @return the number of items filed when the row is now settled; 0 when it was settled before, or stays owed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deliver(UUID decisionId) {
        IncompatibleReleaseDecision decision = incompatibleRows.lockById(decisionId)
            .filter(row -> row.getNoticesFiledAt() == null).orElse(null);
        if (decision == null) {
            return 0;
        }
        TransfusionRequest request = decision.getRelease().getRequest();
        BloodUnit unit = decision.getRelease().getBloodUnit();
        UUID hospitalId = decision.getHospital().getId();
        Patient patient = request.getPatient();
        String patientName = patient != null ? patient.getFullName() : null;
        UUID issuer = decision.getIssuedByUserId();
        UUID encounterId = request.getEncounter() != null ? request.getEncounter().getId() : null;
        Staff requester = request.getRequestedBy();
        UUID requesterId = requester != null && requester.getUser() != null ? requester.getUser().getId() : null;
        List<UUID> directors = holders(hospitalId, DIRECTOR_ROLE_CODES);
        List<CreateInBasketItemRequest> items = new ArrayList<>();
        Object[] unitArg = {unit.getUnitNumber()};
        boolean complete = true;

        if (decision.countersignOwed()) {
            CreateInBasketItemRequest countersign = notice(decision, patient, encounterId, COUNTERSIGN_REFERENCE_TYPE,
                InBasketPriority.CRITICAL);
            countersign.setTitleKey(COUNTERSIGN_TITLE_KEY);
            countersign.setBodyKey(COUNTERSIGN_BODY_KEY);
            String issuerName = userRepository.findById(issuer).map(TransfusionMapper::userName).orElse("");
            Object[] args = {unit.getUnitNumber(), patientName, issuerName, decision.getCountersignDeadlineHours()};
            Collection<UUID> signers = signers(hospitalId, issuer, directors, requesterId);
            complete &= !signers.isEmpty();
            signers.forEach(signer -> items.add(copyFor(signer, countersign, unitArg, args)));
        }
        if (decision.monitoringOwed()) {
            Set<UUID> bedside = new LinkedHashSet<>();
            if (requesterId != null) {
                bedside.add(requesterId);
            }
            if (decision.getAuthoriserUserId() != null) {
                bedside.add(decision.getAuthoriserUserId());
            }
            Collection<UUID> watchers = whoCanAct(bedside, hospitalId, MONITOR_ROLE_CODES);
            complete &= !watchers.isEmpty();
            CreateInBasketItemRequest monitoring = notice(decision, patient, encounterId, MONITORING_REFERENCE_TYPE,
                InBasketPriority.URGENT);
            monitoring.setTitleKey(MONITORING_TITLE_KEY);
            monitoring.setBodyKey(MONITORING_BODY_KEY);
            Object[] monitoringArgs = {unit.getUnitNumber(), patientName, IncompatibleReleaseAuthority.SLOW_START_FIRST_ML,
                IncompatibleReleaseAuthority.SLOW_START_SECOND_ML};
            watchers.forEach(watcher -> items.add(copyFor(watcher, monitoring, unitArg, monitoringArgs)));

            CreateInBasketItemRequest prompt = notice(decision, patient, encounterId, HAEMOVIGILANCE_REFERENCE_TYPE,
                InBasketPriority.URGENT);
            prompt.setTitleKey(HAEMOVIGILANCE_TITLE_KEY);
            if (decision.getAntibodyStatus() == IncompatibleAntibodyStatus.NO_ANTIBODY_ON_RECORD) {
                // Nothing on record: the laboratory is asked to record the work-up, so the permanent alert follows the patient.
                prompt.setBodyKey(HAEMOVIGILANCE_NO_ANTIBODY_BODY_KEY);
            } else {
                prompt.setBodyKey(HAEMOVIGILANCE_BODY_KEY);
            }
            Object[] promptArgs = {unit.getUnitNumber(), patientName};
            Set<UUID> prompted = new LinkedHashSet<>(watchers);
            prompted.addAll(directors);
            prompted.forEach(reader -> items.add(copyFor(reader, prompt, unitArg, promptArgs)));
        }
        // In this transaction: an item that cannot be filed undoes everything here, and the sweep delivers again.
        items.forEach(inBasketService::createItem);
        if (!complete) {
            log.error("Incompatible release decision {} at hospital {}: a notice has nobody who can act on it; "
                + "what could be filed was filed, the rest stays owed", decision.getId(), hospitalId);
            return 0;
        }
        decision.setNoticesFiledAt(LocalDateTime.now(clock));
        incompatibleRows.save(decision);
        return items.size();
    }

    /**
     * Whom a fallback release alerts and asks to countersign: the laboratory directors, the clinician who asked
     * for the blood and the physicians on call now, each only if the countersignature accepts them; with none,
     * everybody at the hospital it accepts. Never the issuer.
     */
    private Collection<UUID> signers(UUID hospitalId, UUID issuer, List<UUID> directors, UUID requesterId) {
        Set<UUID> asked = new LinkedHashSet<>(directors);
        if (requesterId != null) {
            asked.add(requesterId);
        }
        asked.addAll(onCallNow(hospitalId));
        asked.remove(issuer);
        List<UUID> able = asked.stream()
            .filter(user -> assignmentRepository.existsActiveWitness(user, hospitalId, IncompatibleReleaseAuthority.AUTHORISER_ROLE_CODES))
            .toList();
        if (!able.isEmpty()) {
            return able;
        }
        return holders(hospitalId, IncompatibleReleaseAuthority.AUTHORISER_ROLE_CODES).stream()
            .filter(user -> !Objects.equals(user, issuer)).toList();
    }

    /** The staff on the on-call rota of the hospital at this moment; the rota is optional, an empty one is usual. */
    private List<UUID> onCallNow(UUID hospitalId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<UUID> users = new ArrayList<>();
        for (OnCallSchedule entry : onCallRepository.findByHospitalAndWindow(hospitalId, now, now)) {
            Staff staff = entry.getStaff();
            if (staff != null && staff.getUser() != null) {
                users.add(staff.getUser().getId());
            }
        }
        return users;
    }

    /**
     * Those named who hold, now and at this hospital, a role that lets them do the task; when none does, everyone
     * at the hospital who holds such a role. An item filed always reaches somebody who can act on it.
     */
    private Collection<UUID> whoCanAct(Set<UUID> named, UUID hospitalId, Set<String> roles) {
        List<UUID> able = named.stream()
            .filter(user -> assignmentRepository.existsActiveWitness(user, hospitalId, roles)).toList();
        return able.isEmpty() ? holders(hospitalId, roles) : able;
    }

    private List<UUID> holders(UUID hospitalId, Set<String> roles) {
        return assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, roles)
            .stream().map(User::getId).distinct().toList();
    }

    /** One notice before its keys and its reader: a task about this decision, for this patient. */
    private static CreateInBasketItemRequest notice(IncompatibleReleaseDecision decision, Patient patient, UUID encounterId,
                                                    String referenceType, InBasketPriority priority) {
        CreateInBasketItemRequest item = new CreateInBasketItemRequest();
        item.setHospitalId(decision.getHospital().getId());
        item.setItemType(InBasketItemType.TASK);
        item.setPriority(priority);
        item.setReferenceId(decision.getId());
        item.setReferenceType(referenceType);
        item.setEncounterId(encounterId);
        item.setPatientId(patient != null ? patient.getId() : decision.getPatientId());
        item.setPatientName(patient != null ? patient.getFullName() : null);
        return item;
    }

    /**
     * One reader's copy of a notice. {@code template} carries the title and body keys: the reader's copy is
     * rendered from them in their language, and the stored text is the staff-language copy.
     */
    private static CreateInBasketItemRequest copyFor(UUID recipient, CreateInBasketItemRequest template,
                                                     Object[] titleArgs, Object[] bodyArgs) {
        String titleKey = template.getTitleKey();
        String bodyKey = template.getBodyKey();
        CreateInBasketItemRequest item = new CreateInBasketItemRequest();
        item.setRecipientUserId(recipient);
        item.setHospitalId(template.getHospitalId());
        item.setItemType(template.getItemType());
        item.setPriority(template.getPriority());
        item.setReferenceId(template.getReferenceId());
        item.setReferenceType(template.getReferenceType());
        item.setEncounterId(template.getEncounterId());
        item.setPatientId(template.getPatientId());
        item.setPatientName(template.getPatientName());
        item.setTitleKey(titleKey);
        item.setTitleArgs(titleArgs);
        item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, titleKey, titleArgs));
        item.setBodyKey(bodyKey);
        item.setBodyArgs(bodyArgs);
        item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, bodyKey, bodyArgs));
        return item;
    }

    // ── The overdue countersignature ────────────────────────────────────

    /** The countersignatures past their deadline that nobody was told about yet, the oldest first. */
    public List<UUID> overdueCountersignIds() {
        return incompatibleRows.findOverdueCountersignIds(LocalDateTime.now(clock));
    }

    /**
     * One countersignature past its deadline is escalated to the laboratory director, once, and listed for audit.
     * The alert is filed in the transaction that marks the row as escalated: if it cannot be delivered the mark is
     * not kept, and the next sweep tries again. With no laboratory director the row is left to the next sweep,
     * loudly: marking it escalated would say somebody was told.
     *
     * @return the number of items filed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int escalateOverdueCountersign(UUID decisionId) {
        IncompatibleReleaseDecision decision = incompatibleRows.lockById(decisionId)
            .filter(row -> row.countersignOwed() && row.getCountersignEscalatedAt() == null).orElse(null);
        if (decision == null) {
            return 0;
        }
        UUID hospitalId = decision.getHospital().getId();
        List<UUID> directors = holders(hospitalId, DIRECTOR_ROLE_CODES);
        if (directors.isEmpty()) {
            log.error("Overdue countersignature of incompatible release decision {} has no laboratory director to go to",
                decision.getId());
            return 0;
        }
        Patient patient = decision.getRelease().getRequest().getPatient();
        String unitNumber = decision.getRelease().getBloodUnit().getUnitNumber();
        Object[] titleArgs = {unitNumber};
        Object[] bodyArgs = {unitNumber, patient != null ? patient.getFullName() : null,
            decision.getCountersignDeadlineHours()};
        CreateInBasketItemRequest overdue = notice(decision, patient, null, OVERDUE_REFERENCE_TYPE, InBasketPriority.CRITICAL);
        overdue.setTitleKey(OVERDUE_TITLE_KEY);
        overdue.setBodyKey(OVERDUE_BODY_KEY);
        decision.setCountersignEscalatedAt(LocalDateTime.now(clock));
        incompatibleRows.save(decision);
        for (UUID director : directors) {
            // In this transaction, not after it: a failure here undoes the mark above.
            inBasketService.createItem(copyFor(director, overdue, titleArgs, bodyArgs));
        }
        UUID id = decision.getId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .eventType(AuditEventType.INCOMPATIBLE_COUNTERSIGN_OVERDUE)
                    .status(AuditStatus.SUCCESS)
                    .eventDescription(
                        "Incompatible release countersignature overdue, escalated to the laboratory director: decision " + id)
                    .entityType(DECISION_ENTITY_TYPE)
                    .resourceId(String.valueOf(id))
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Audit not written for the overdue countersignature of incompatible release decision {}: {}", id,
                    ex.getClass().getSimpleName());
            }
        });
        return directors.size();
    }
}
