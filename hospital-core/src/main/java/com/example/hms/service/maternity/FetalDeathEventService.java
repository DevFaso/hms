package com.example.hms.service.maternity;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.BabyOutcome;
import com.example.hms.enums.FetalDeathEventSource;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.User;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.FetalDeathEvent;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.FetalDeathEventRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D1714 (user decision 2026-11-13): a recorded stillbirth automatically creates a linked fetal-death event that
 * needs no patient account. Every path that records or corrects a delivery outcome goes through here
 * ({@code FetalDeathEventPathGuardTest}).
 *
 * <ul>
 *   <li>One event per stillborn baby, linked to the delivery, the mother and the baby's order, holding the date and
 *       time, the gestation at delivery if known, the hospital, the recorder, the source and "no signs of life".</li>
 *   <li>Idempotent: a baby that already has a live event never gets a second (and the database refuses one,
 *       {@code uq_fetal_death_live_per_baby}).</li>
 *   <li>A correction never deletes anything: stillborn to live supersedes the event with who, when and why; live to
 *       stillborn creates one with the reason. Every change is audited (ids, orders and outcomes only).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FetalDeathEventService {

    static final String ENTITY_TYPE = "FETAL_DEATH_EVENT";
    static final int REASON_MAX = 500;

    private final FetalDeathEventRepository fetalDeathEventRepository;
    private final DeliveryRecordRepository deliveryRecordRepository;
    private final UserRepository userRepository;
    private final AuditEventLogService auditEventLogService;

    /**
     * QA D1802: the death certificates a correction to born alive left contradicted (setter-injected; absent in
     * hand-built tests).
     */
    private StillbirthCertificateConflicts certificateConflicts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCertificateConflicts(StillbirthCertificateConflicts certificateConflicts) {
        this.certificateConflicts = certificateConflicts;
    }

    /**
     * What a correction did. {@code changed} is false when the baby already had that outcome.
     * {@code contradictedCertificates}: the live death certificates that still declare this baby's stillbirth after
     * a correction to born alive (QA D1802; they are left unchanged).
     */
    public record Correction(boolean changed, BabyOutcome from, BabyOutcome to, FetalDeathEvent created,
                             FetalDeathEvent superseded, List<UUID> contradictedCertificates) {

        public Correction(boolean changed, BabyOutcome from, BabyOutcome to, FetalDeathEvent created,
                          FetalDeathEvent superseded) {
            this(changed, from, to, created, superseded, List.of());
        }
    }

    /**
     * Create the fetal-death event of each stillborn baby of a saved delivery that has none yet. Idempotent: calling
     * it again (a re-save, a retry) creates nothing more.
     *
     * @return the events created by this call
     */
    @Transactional
    public List<FetalDeathEvent> recordOutcomes(DeliveryRecord delivery, UUID recorderUserId) {
        List<FetalDeathEvent> created = new ArrayList<>();
        if (delivery == null || delivery.getId() == null) {
            return created;
        }
        User recorder = userRef(recorderUserId);
        for (int order : delivery.stillbornOrders()) {
            ensureEvent(delivery, order, FetalDeathEventSource.DELIVERY, recorder, null).ifPresent(created::add);
        }
        return created;
    }

    /**
     * Correct one baby's outcome. Nothing is deleted: a stillborn baby now recorded live supersedes its event (kept,
     * with the reason, who and when); a live baby now recorded stillborn gets an event carrying the reason. A baby
     * already registered as a patient stays registered; the newborn registration flags it for review.
     */
    @Transactional
    public Correction correctOutcome(DeliveryRecord delivery, int birthOrder, BabyOutcome outcome, String reason,
                                     UUID userId) {
        if (outcome == null) {
            throw new FieldBusinessException("outcome", "labor.delivery.babyOutcomeRequired");
        }
        int babies = Math.max(delivery.getNumberOfInfants(), 1);
        if (birthOrder < 1 || birthOrder > babies) {
            throw new FieldBusinessException("birthOrder", "labor.delivery.babyNotFound", birthOrder);
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new FieldBusinessException("reason", "labor.delivery.outcomeCorrectionReasonRequired");
        }
        if (why.length() > REASON_MAX) {
            throw new FieldBusinessException("reason", "labor.delivery.outcomeCorrectionReasonTooLong", REASON_MAX);
        }
        BabyOutcome from = delivery.outcomeOf(birthOrder);
        if (from == outcome) {
            // Rex on #1144: a correction that changes nothing is still a recorded act, with its reason and actor.
            auditCorrection(delivery, birthOrder, from, outcome, why, userId, false);
            return new Correction(false, from, outcome, null, null);
        }
        materializeOutcomes(delivery);
        // The caller saves the delivery and re-runs the newborn registration (NewbornRegistrationGuardTest).
        delivery.putBabyOutcome(birthOrder, outcome);

        User actor = userRef(userId);
        FetalDeathEvent created = null;
        FetalDeathEvent superseded = null;
        List<UUID> contradicted = List.of();
        if (outcome == BabyOutcome.STILLBORN) {
            created = ensureEvent(delivery, birthOrder, FetalDeathEventSource.DELIVERY, actor, why).orElse(null);
            if (certificateConflicts != null) {
                certificateConflicts.correctedToStillborn(delivery, birthOrder, userId);
            }
        } else {
            superseded = fetalDeathEventRepository
                .findFirstByDeliveryRecord_IdAndBirthOrderAndSupersededAtIsNull(delivery.getId(), birthOrder)
                .map(event -> supersede(event, actor, why))
                .orElse(null);
            // QA D1802 (provisional): the correction stands, the certificate is left as it is and nobody is
            // reactivated; the contradiction is audited and handed to the certificate reviewers.
            if (certificateConflicts != null) {
                contradicted = certificateConflicts.correctedToLive(delivery, birthOrder, superseded, why, userId);
            }
        }
        auditCorrection(delivery, birthOrder, from, outcome, why, userId, true);
        return new Correction(true, from, outcome, created, superseded, contradicted);
    }

    /**
     * Rex on #1144: every correction is audited with its reason and its actor, whether or not an event was created
     * or superseded (an unknown baby made live, or a correction to the outcome already recorded).
     */
    private void auditCorrection(DeliveryRecord delivery, int birthOrder, BabyOutcome from, BabyOutcome to,
                                 String reason, UUID userId, boolean changed) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deliveryRecordId", String.valueOf(delivery.getId()));
        details.put("birthOrder", birthOrder);
        details.put("from", from == null ? "UNKNOWN" : from.name());
        details.put("to", to.name());
        details.put("changed", changed);
        details.put("reason", reason);
        audit(AuditEventType.DELIVERY_BABY_OUTCOME_CORRECTED, "DELIVERY_RECORD", delivery.getId(), delivery, userId,
            "Baby outcome corrected on a delivery record", details);
    }

    /**
     * The one-time historical reconciliation of one delivery recorded not live before V293: one event, for the first
     * baby, from the recorded facts only (the delivery's birth time, its recorded gestation or none, its recorder).
     * A single flag cannot say which of several babies was stillborn, so nothing is guessed about twins.
     *
     * @return the event created, or empty when the delivery is live, recorded per baby, or already has an event
     */
    @Transactional
    public Optional<FetalDeathEvent> backfillHistorical(UUID deliveryRecordId) {
        DeliveryRecord delivery = deliveryRecordRepository.findById(deliveryRecordId).orElse(null);
        if (delivery == null || delivery.isLiveBirth() || !delivery.getBabyOutcomes().isEmpty()
            || fetalDeathEventRepository.existsByDeliveryRecord_Id(delivery.getId())) {
            return Optional.empty();
        }
        return ensureEvent(delivery, 1, FetalDeathEventSource.DELIVERY_HISTORICAL, delivery.getDocumentedBy(), null);
    }

    /** The events of a delivery, superseded ones included (the read side of the delivery record). */
    @Transactional(readOnly = true)
    public List<FetalDeathEvent> eventsOf(DeliveryRecord delivery) {
        if (delivery == null || delivery.getId() == null) {
            return List.of();
        }
        return fetalDeathEventRepository.findByDeliveryRecord_IdOrderByBirthOrderAscCreatedAtAsc(delivery.getId());
    }

    /**
     * The read side: every event of a delivery, superseded ones included, with the reason a correction gave, who
     * made it and when.
     */
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.mortality.FetalDeathEventDTO> eventDtosOf(DeliveryRecord delivery) {
        return eventsOf(delivery).stream().map(FetalDeathEventService::toDto).toList();
    }

    static com.example.hms.payload.dto.mortality.FetalDeathEventDTO toDto(FetalDeathEvent event) {
        com.example.hms.service.support.StillbirthClassification.Result result =
            com.example.hms.service.support.StillbirthClassification.classify(event.getGestationalAgeWeeks(),
                event.isNoSignsOfLife());
        return com.example.hms.payload.dto.mortality.FetalDeathEventDTO.builder()
            .id(event.getId())
            .deliveryRecordId(event.getDeliveryRecord() == null ? null : event.getDeliveryRecord().getId())
            .motherPatientId(event.getMotherPatient() == null ? null : event.getMotherPatient().getId())
            .birthOrder(event.getBirthOrder())
            .occurredAt(event.getOccurredAt())
            .gestationalAgeWeeks(event.getGestationalAgeWeeks())
            .noSignsOfLife(event.isNoSignsOfLife())
            .source(event.getSource())
            .recordedByName(event.getRecordedBy() == null ? null : event.getRecordedBy().getUsername())
            .whoStillbirth(!event.isSuperseded() && result.whoStillbirth())
            .icdFetalDeath(!event.isSuperseded() && result.icdFetalDeath())
            .classificationIncomplete(!event.isSuperseded() && result.incomplete())
            .creationReason(event.getCreationReason())
            .supersededAt(event.getSupersededAt())
            .supersededByName(event.getSupersededBy() == null ? null : event.getSupersededBy().getUsername())
            .supersedeReason(event.getSupersedeReason())
            .build();
    }

    private Optional<FetalDeathEvent> ensureEvent(DeliveryRecord delivery, int order, FetalDeathEventSource source,
                                                  User recorder, String creationReason) {
        if (fetalDeathEventRepository
            .findFirstByDeliveryRecord_IdAndBirthOrderAndSupersededAtIsNull(delivery.getId(), order).isPresent()) {
            return Optional.empty();
        }
        FetalDeathEvent saved = fetalDeathEventRepository.save(FetalDeathEvent.builder()
            .deliveryRecord(delivery)
            .motherPatient(delivery.getPatient())
            .hospital(delivery.getHospital())
            .birthOrder(order)
            .occurredAt(delivery.getBirthDateTime())
            .gestationalAgeWeeks(delivery.getGestationalAgeWeeksAtBirth())
            .noSignsOfLife(true)
            .source(source)
            .recordedBy(recorder)
            .creationReason(creationReason)
            .build());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deliveryRecordId", String.valueOf(delivery.getId()));
        details.put("birthOrder", order);
        details.put("source", source.name());
        details.put("gestationKnown", delivery.getGestationalAgeWeeksAtBirth() != null);
        audit(AuditEventType.FETAL_DEATH_EVENT_RECORDED, ENTITY_TYPE, saved.getId(), delivery,
            recorder == null ? null : recorder.getId(), "Fetal-death event recorded for a stillborn baby", details);
        return Optional.of(saved);
    }

    private FetalDeathEvent supersede(FetalDeathEvent event, User actor, String reason) {
        event.setSupersededAt(LocalDateTime.now());
        event.setSupersededBy(actor);
        event.setSupersedeReason(reason);
        FetalDeathEvent saved = fetalDeathEventRepository.save(event);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deliveryRecordId", String.valueOf(event.getDeliveryRecord().getId()));
        details.put("birthOrder", event.getBirthOrder());
        audit(AuditEventType.FETAL_DEATH_EVENT_SUPERSEDED, ENTITY_TYPE, saved.getId(), event.getDeliveryRecord(),
            actor == null ? null : actor.getId(), "Fetal-death event superseded by a corrected baby outcome", details);
        return saved;
    }

    /**
     * A delivery recorded before V293 answers from its single flag. Before one baby's outcome changes, the others
     * are written out as the flag reads them, so the change cannot alter them; an unknown one stays unwritten.
     */
    private static void materializeOutcomes(DeliveryRecord delivery) {
        if (!delivery.getBabyOutcomes().isEmpty()) {
            return;
        }
        Map<Integer, BabyOutcome> current = new LinkedHashMap<>();
        for (int order = 1; order <= Math.max(delivery.getNumberOfInfants(), 1); order++) {
            BabyOutcome known = delivery.outcomeOf(order);
            if (known != null) {
                current.put(order, known);
            }
        }
        current.forEach(delivery::putBabyOutcome);
    }

    private User userRef(UUID userId) {
        return userId == null ? null : userRepository.getReferenceById(userId);
    }

    private void audit(AuditEventType type, String entityType, UUID resourceId, DeliveryRecord delivery, UUID userId,
                       String description, Map<String, Object> details) {
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .eventType(type)
            .status(AuditStatus.SUCCESS)
            .entityType(entityType)
            .resourceId(resourceId == null ? null : resourceId.toString())
            .patientId(delivery.getPatient() == null ? null : delivery.getPatient().getId())
            .hospitalId(delivery.getHospital() == null ? null : delivery.getHospital().getId())
            .userId(userId)
            .userName(HospitalContextHolder.getContextOrEmpty().getPrincipalUsername())
            .eventDescription(description)
            .details(details)
            .build();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(event);
            } catch (RuntimeException ex) {
                log.warn("[FETAL_DEATH] Could not audit {}: {}", type, ex.getClass().getSimpleName());
            }
        });
    }
}
