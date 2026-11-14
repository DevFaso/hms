package com.example.hms.service.maternity;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.BabyOutcome;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.labor.DeliveryNewborn;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.FetalDeathEvent;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.DeathRecordRepository;
import com.example.hms.repository.DeliveryNewbornRepository;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.FetalDeathEventRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D1802, provisional rule (user decision 2026-11-14, pending clinical approval, approvals register): a baby's
 * outcome may be corrected from stillborn to born alive while a death certificate declares that stillbirth.
 *
 * <ul>
 *   <li>The correction stands (with its required reason) and the certificate is left unchanged: nothing here edits a
 *       death record or reactivates a patient. Born alive does not mean alive now.</li>
 *   <li>Both contradictory records are audited, each naming the other.</li>
 *   <li>An URGENT reconciliation task goes to whoever may amend a death certificate at the certificate's hospital
 *       ({@link #REVIEWER_ROLE_CODES}), one per conflict and reader. It says what to do: a baby born alive who then
 *       died is a neonatal death to classify on the certificate; a baby who is alive is reopened through the urgent
 *       death-record dispute.</li>
 *   <li>The task clears once the contradiction ends: the certificate is amended out of the stillbirth classification
 *       or marked entered in error, or the outcome is corrected back to stillborn.</li>
 *   <li>Until then the register counts that baby once and marks the row unresolved ({@link #liveBornIdentities}).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StillbirthCertificateConflicts {

    static final String REFERENCE_TYPE = "STILLBIRTH_CERTIFICATE_CONFLICT";
    static final String TITLE_KEY = "mortality.stillbirthConflict.title";
    static final String BODY_KEY = "mortality.stillbirthConflict.body";

    /**
     * Who may amend a death certificate today (MortalityController RECORDER, less the platform super-admin): the
     * hospital's doctors, surgeons and midwives. Not the hospital administrator, whose only in-basket item is the
     * dispute review (E9 #67, InBasketRecipientGuardTest).
     */
    static final Set<String> REVIEWER_ROLE_CODES = Set.of(
        SecurityConstants.ROLE_DOCTOR, SecurityConstants.ROLE_SURGEON, SecurityConstants.ROLE_MIDWIFE);

    private final DeathRecordRepository deathRecordRepository;
    private final DeliveryNewbornRepository deliveryNewbornRepository;
    private final DeliveryRecordRepository deliveryRecordRepository;
    private final FetalDeathEventRepository fetalDeathEventRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final InBasketService inBasketService;
    private final AuditEventLogService auditEventLogService;

    /**
     * A baby was just corrected to born alive: each live certificate that declares its stillbirth is left as it is,
     * audited with the delivery, and handed to the certificate reviewers.
     *
     * @return the ids of the certificates that still declare the stillbirth
     */
    public List<UUID> correctedToLive(DeliveryRecord delivery, int birthOrder, FetalDeathEvent superseded,
                                      String reason, UUID actorId) {
        List<UUID> conflicting = new ArrayList<>();
        for (DeathRecord certificate : certificatesDeclaring(delivery, birthOrder)) {
            conflicting.add(certificate.getId());
            auditConflict(certificate, delivery, birthOrder, superseded, actorId);
            fileReconciliation(certificate, birthOrder, reason);
        }
        return conflicting;
    }

    /**
     * The baby is stillborn again: the contradiction has ended for each certificate of this baby.
     *
     * @param from the outcome the delivery recorded before this correction: only a baby recorded born alive was in
     *             conflict with its stillbirth certificate
     */
    public void correctedToStillborn(DeliveryRecord delivery, int birthOrder, BabyOutcome from, UUID actorId) {
        boolean wasContradicted = from == BabyOutcome.LIVE;
        for (DeathRecord certificate : certificatesDeclaring(delivery, birthOrder)) {
            reconcile(certificate, "OUTCOME_CORRECTED_TO_STILLBORN", wasContradicted, actorId);
        }
    }

    /**
     * A certificate was amended or marked entered in error. Once it no longer declares a stillbirth, the conflict it
     * was in (if any) is reconciled: audited, and any reconciliation task filed for it is done.
     *
     * @param declaredStillbirthBefore whether the certificate declared the stillbirth before this change
     *                                 ({@link #declaresStillbirth}, read before the edit)
     */
    public void certificateChanged(DeathRecord certificate, boolean declaredStillbirthBefore, UUID actorId) {
        if (certificate == null || certificate.getId() == null || declaresStillbirth(certificate)) {
            return;
        }
        // Rex on #1152: the conflict is judged from the records, not from the tasks. A hospital with no reviewer had
        // no task filed, and its reconciliation is still audited.
        boolean wasContradicted = declaredStillbirthBefore && recordedLiveBorn(certificate);
        reconcile(certificate, certificate.isEnteredInError() ? "CERTIFICATE_ENTERED_IN_ERROR" : "CERTIFICATE_AMENDED",
            wasContradicted, actorId);
    }

    /**
     * Of the given babies ("deliveryId#birthOrder"), those their delivery now records as born alive. A stillbirth
     * certificate for one of them contradicts the delivery: the register shows it unresolved.
     */
    public Set<String> liveBornIdentities(Collection<String> identities) {
        Map<UUID, List<Integer>> orders = new HashMap<>();
        for (String key : identities) {
            int at = key.indexOf('#');
            orders.computeIfAbsent(UUID.fromString(key.substring(0, at)), id -> new ArrayList<>())
                .add(Integer.parseInt(key.substring(at + 1)));
        }
        Set<String> live = new HashSet<>();
        if (orders.isEmpty()) {
            return live;
        }
        for (DeliveryRecord delivery : deliveryRecordRepository.findAllById(orders.keySet())) {
            for (int order : orders.getOrDefault(delivery.getId(), List.of())) {
                if (delivery.outcomeOf(order) == BabyOutcome.LIVE) {
                    live.add(delivery.getId() + "#" + order);
                }
            }
        }
        return live;
    }

    /** The live stillbirth certificates of one baby: through any event of it, or through its registered patient. */
    List<DeathRecord> certificatesDeclaring(DeliveryRecord delivery, int birthOrder) {
        if (delivery == null || delivery.getId() == null) {
            return List.of();
        }
        Map<UUID, DeathRecord> found = new LinkedHashMap<>();
        List<UUID> eventIds = fetalDeathEventRepository
            .findByDeliveryRecord_IdOrderByBirthOrderAscCreatedAtAsc(delivery.getId()).stream()
            .filter(event -> event.getBirthOrder() == birthOrder)
            .map(FetalDeathEvent::getId)
            .toList();
        if (!eventIds.isEmpty()) {
            deathRecordRepository.findLiveByFetalDeathEventIdIn(eventIds)
                .forEach(death -> found.putIfAbsent(death.getId(), death));
        }
        for (DeliveryNewborn link : deliveryNewbornRepository.findByDeliveryRecord_IdOrderByBirthOrderAsc(delivery.getId())) {
            if (link.getBirthOrder() == birthOrder && link.getNewbornPatient() != null) {
                deathRecordRepository.findByPatient_Id(link.getNewbornPatient().getId())
                    .ifPresent(death -> found.putIfAbsent(death.getId(), death));
            }
        }
        return found.values().stream().filter(StillbirthCertificateConflicts::declaresStillbirth).toList();
    }

    /** Whether the delivery records as born alive the baby this certificate is for (through its event or patient). */
    boolean recordedLiveBorn(DeathRecord certificate) {
        if (certificate.getFetalDeathEventId() != null) {
            Optional<FetalDeathEvent> event = fetalDeathEventRepository.findById(certificate.getFetalDeathEventId());
            if (event.isPresent() && event.get().getDeliveryRecord() != null
                && event.get().getDeliveryRecord().outcomeOf(event.get().getBirthOrder()) == BabyOutcome.LIVE) {
                return true;
            }
        }
        if (certificate.getPatient() == null || certificate.getPatient().getId() == null) {
            return false;
        }
        return deliveryNewbornRepository.findByNewbornPatient_Id(certificate.getPatient().getId())
            .filter(link -> link.getDeliveryRecord() != null)
            .map(link -> link.getDeliveryRecord().outcomeOf(link.getBirthOrder()) == BabyOutcome.LIVE)
            .orElse(false);
    }

    public static boolean declaresStillbirth(DeathRecord death) {
        return !death.isEnteredInError() && Boolean.TRUE.equals(death.getPerinatalDeath())
            && death.getPerinatalType() == PerinatalDeathType.STILLBIRTH;
    }

    /** One reference per certificate: the in-basket keeps one item per reference and reader. */
    static UUID reference(UUID deathRecordId) {
        return UUID.nameUUIDFromBytes((REFERENCE_TYPE + "@" + deathRecordId).getBytes(StandardCharsets.UTF_8));
    }

    private void fileReconciliation(DeathRecord certificate, int birthOrder, String reason) {
        if (certificate.getHospital() == null || certificate.getHospital().getId() == null) {
            return;
        }
        UUID hospitalId = certificate.getHospital().getId();
        String babyName = certificate.getPatient() == null ? null : certificate.getPatient().getFullName();
        Object[] bodyArgs = {babyName, birthOrder, reason};
        List<com.example.hms.model.User> reviewers =
            assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, REVIEWER_ROLE_CODES);
        if (reviewers.isEmpty()) {
            // The conflict is audited all the same; nobody at this hospital may amend the certificate yet.
            log.warn("[STILLBIRTH_CONFLICT] No certificate reviewer at hospital {}: no reconciliation task filed",
                hospitalId);
        }
        for (com.example.hms.model.User reviewer : reviewers) {
            CreateInBasketItemRequest item = new CreateInBasketItemRequest();
            item.setRecipientUserId(reviewer.getId());
            item.setHospitalId(hospitalId);
            item.setItemType(InBasketItemType.TASK);
            item.setPriority(InBasketPriority.URGENT);
            item.setReferenceId(reference(certificate.getId()));
            item.setReferenceType(REFERENCE_TYPE);
            item.setPatientId(certificate.getPatient() == null ? null : certificate.getPatient().getId());
            item.setPatientName(babyName);
            item.setTitleKey(TITLE_KEY);
            item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, TITLE_KEY));
            item.setBodyKey(BODY_KEY);
            item.setBodyArgs(bodyArgs);
            item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, BODY_KEY, bodyArgs));
            inBasketService.createItem(item);
        }
    }

    /**
     * Clears the reconciliation tasks and audits the reconciliation. Rex on #1152: the audit follows the conflict,
     * not the task count: a conflict nobody could be tasked with (no reviewer at the hospital) is reconciled and
     * audited all the same. A change that ends no conflict and clears no task records nothing.
     */
    private void reconcile(DeathRecord certificate, String how, boolean wasContradicted, UUID actorId) {
        int removed = inBasketService.removeAllItemsForReference(reference(certificate.getId()), REFERENCE_TYPE);
        if (removed == 0 && !wasContradicted) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deathRecordId", String.valueOf(certificate.getId()));
        details.put("resolution", how);
        details.put("tasksCleared", removed);
        audit(AuditEventType.STILLBIRTH_CERTIFICATE_CONFLICT_RECONCILED, "DEATH_RECORD", certificate.getId(),
            certificate.getPatient() == null ? null : certificate.getPatient().getId(),
            certificate.getHospital() == null ? null : certificate.getHospital().getId(), actorId,
            "Stillbirth certificate conflict reconciled", details);
    }

    /** Each of the two contradictory records gets its audit row, naming the other (ids, order and outcome only). */
    private void auditConflict(DeathRecord certificate, DeliveryRecord delivery, int birthOrder,
                               FetalDeathEvent superseded, UUID actorId) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("deathRecordId", String.valueOf(certificate.getId()));
        link.put("deliveryRecordId", String.valueOf(delivery.getId()));
        link.put("birthOrder", birthOrder);
        link.put("fetalDeathEventId", superseded == null ? null : String.valueOf(superseded.getId()));
        link.put("deliveryOutcome", BabyOutcome.LIVE.name());
        link.put("certificateDeclares", PerinatalDeathType.STILLBIRTH.name());
        link.put("certificateChanged", false);
        UUID hospitalId = delivery.getHospital() == null ? null : delivery.getHospital().getId();
        audit(AuditEventType.STILLBIRTH_CERTIFICATE_CONFLICT, "DEATH_RECORD", certificate.getId(),
            certificate.getPatient() == null ? null : certificate.getPatient().getId(),
            certificate.getHospital() == null ? hospitalId : certificate.getHospital().getId(), actorId,
            "Death certificate declares a stillbirth the delivery now records as born alive", link);
        audit(AuditEventType.STILLBIRTH_CERTIFICATE_CONFLICT, "DELIVERY_RECORD", delivery.getId(),
            delivery.getPatient() == null ? null : delivery.getPatient().getId(), hospitalId, actorId,
            "Delivery records as born alive a baby a death certificate declares stillborn", link);
    }

    private void audit(AuditEventType type, String entityType, UUID resourceId, UUID patientId, UUID hospitalId,
                       UUID userId, String description, Map<String, Object> details) {
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .eventType(type)
            .status(AuditStatus.SUCCESS)
            .entityType(entityType)
            .resourceId(resourceId == null ? null : resourceId.toString())
            .patientId(patientId)
            .hospitalId(hospitalId)
            .userId(userId)
            .userName(HospitalContextHolder.getContextOrEmpty().getPrincipalUsername())
            .eventDescription(description)
            .details(details)
            .build();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(event);
            } catch (RuntimeException ex) {
                log.warn("[STILLBIRTH_CONFLICT] Could not audit {}: {}", type, ex.getClass().getSimpleName());
            }
        });
    }
}
