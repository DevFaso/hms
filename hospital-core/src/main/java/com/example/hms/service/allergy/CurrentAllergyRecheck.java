package com.example.hms.service.allergy;

import com.example.hms.cdshooks.CdsCriticalBlockException;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.PrescriptionServiceImpl;
import com.example.hms.service.pharmacy.DispenseVerificationService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D1563 / D1555 (round 43): an allergy recorded after the order was signed was never checked
 * again. Amoxicillin was given on the eMAR (no card, verify allPassed=true, GIVEN 200) and
 * dispensed at the pharmacy (work queue needsAttention=false, no allergy on the panel, 201) to a
 * patient with a SEVERE penicillin allergy recorded after the signature; the same with paracetamol
 * and a paracetamol allergy. The eMAR only showed the cross-reactivity advisory (QA D793/D799), which
 * is silent on a direct match because the prescribing block was meant to stop that.
 *
 * <p>THE ONE RE-CHECK after the signature. Every gate (the bedside check and every recorded dose,
 * the pharmacist's verification, every fill and hand-over, the routing to a partner pharmacy, the
 * work queue) reads the order's drug against the patient's CURRENT allergies (at any hospital, as
 * the prescriber's check) with the prescribing rules: a direct match, a brand read as its class
 * ({@link AllergyDrugIdentity}), the safety net for a name that resolves to no ingredient (as
 * {@link UnverifiableAllergyCard}), and the cross-reactivity advisory ({@link CrossReactivityAdvisory},
 * a warning that never refuses).
 *
 * <p>A stop (a direct or class match, or the safety net) refuses the gate's write until the
 * clinician acknowledges THAT allergy with a reason, on the request field {@link #ACK_FIELD}. The
 * acknowledgement is bound to the allergy by the token its card carries ({@link Stop#token}): an
 * allergy edited or added since voids it. Provisional default (2026-11-10, not a clinical
 * sign-off): acknowledge with a reason, at each gate.
 */
@Slf4j
@Component
public class CurrentAllergyRecheck {

    /** The request field the acknowledgement's reason is read from, and the refusal names (every gate). */
    public static final String ACK_FIELD = "allergyAcknowledgementReason";

    /** The prefix of the card's exception key; the rest is the token the request sends back. */
    public static final String KEY_PREFIX = "allergy:";

    private static final String PRESCRIPTION_ENTITY = "PRESCRIPTION";

    /** The gates that re-check, each with its refusal wording and its audit. */
    public enum Gate {
        /** A GIVEN dose on the eMAR (HELD and REFUSED are never asked). */
        MAR_DOSE(AuditEventType.MAR_ALLERGY_ACKNOWLEDGED, "MEDICATION_ADMINISTRATION"),
        /** The pharmacist's verification of the order. */
        PHARMACIST_VERIFICATION(AuditEventType.DISPENSE_ALLERGY_ACKNOWLEDGED, PRESCRIPTION_ENTITY),
        /** A fill (one-step or prepared, a refill's included) or the hand-over of a prepared fill. */
        DISPENSE(AuditEventType.DISPENSE_ALLERGY_ACKNOWLEDGED, "DISPENSE"),
        /** Sending the order to a partner pharmacy, which fills it outside. */
        PARTNER_ROUTING(AuditEventType.DISPENSE_ALLERGY_ACKNOWLEDGED, PRESCRIPTION_ENTITY);

        private final AuditEventType auditType;
        private final String entityType;

        Gate(AuditEventType auditType, String entityType) {
            this.auditType = auditType;
            this.entityType = entityType;
        }

        /** The refusal's message key: the card, then what to do at this gate. */
        String requiredKey() {
            return "allergy.recheck.required." + name();
        }
    }

    private final PatientAllergyRepository allergyRepository;
    private final MedicationCatalogItemRepository catalogue;
    private final AuditEventLogService audit;

    @Autowired
    public CurrentAllergyRecheck(PatientAllergyRepository allergies, MedicationCatalogItemRepository catalogue,
                                 AuditEventLogService audit) {
        this.allergyRepository = allergies;
        this.catalogue = catalogue;
        this.audit = audit;
    }

    /**
     * An allergy stop on this order: its card (carrying the token), the allergy it names and the rule
     * that raised it ({@code documented} or {@code unverifiable}).
     */
    public record Stop(CdsCard card, PatientAllergy allergy, String kind, String token) {

        /** The allergy as the card names it. */
        public String allergen() {
            return AllergyDrugIdentity.displayOf(allergy);
        }
    }

    /** A clinician's acknowledgement of one stop at one gate. */
    public record Acknowledgement(Gate gate, Stop stop, String reason) {
    }

    /* -- Reading ------------------------------------------------------------------------------- */

    /** The patient's allergies on record at any hospital (the prescriber's rule). */
    public List<PatientAllergy> currentAllergies(Prescription rx) {
        if (rx == null || rx.getPatient() == null || rx.getPatient().getId() == null || allergyRepository == null) {
            return List.of();
        }
        return allergyRepository.findByPatient_Id(rx.getPatient().getId());
    }

    /** The allergies of several patients at once (a work-queue page), by patient. */
    public Map<UUID, List<PatientAllergy>> currentAllergies(Collection<UUID> patientIds) {
        Map<UUID, List<PatientAllergy>> byPatient = new HashMap<>();
        if (allergyRepository == null || patientIds == null || patientIds.isEmpty()) {
            return byPatient;
        }
        for (PatientAllergy allergy : allergyRepository.findByPatient_IdIn(List.copyOf(patientIds))) {
            if (allergy.getPatient() != null) {
                byPatient.computeIfAbsent(allergy.getPatient().getId(), id -> new ArrayList<>()).add(allergy);
            }
        }
        return byPatient;
    }

    /** The active items of a hospital's catalogue and the national list, read once for a page. */
    public List<MedicationCatalogItem> activeCatalogue(UUID hospitalId) {
        if (catalogue == null || hospitalId == null) {
            return List.of();
        }
        Page<MedicationCatalogItem> page = catalogue.findActivePage(hospitalId, Pageable.unpaged());
        return page == null ? List.of() : page.getContent();
    }

    /** Page resolution keeps inactive linked items too, just as the write gate does. */
    public List<MedicationCatalogItem> catalogueForOrders(UUID hospitalId, Collection<Prescription> orders) {
        List<MedicationCatalogItem> items = new ArrayList<>(activeCatalogue(hospitalId));
        if (catalogue == null || hospitalId == null) {
            return items;
        }
        List<UUID> missing = orders.stream().map(Prescription::getMedicationCatalogItemId)
            .filter(Objects::nonNull).distinct()
            .filter(id -> items.stream().noneMatch(item -> id.equals(item.getId()))).toList();
        if (!missing.isEmpty()) {
            items.addAll(catalogue.findByHospital_IdAndIdIn(hospitalId, missing));
        }
        return items;
    }

    /** {@link #itemsFor(String, UUID, List)} on the order's own hospital catalogue, its linked item first. */
    public List<MedicationCatalogItem> itemsFor(Prescription rx, String medicationName) {
        UUID hospitalId = rx != null && rx.getHospital() != null ? rx.getHospital().getId() : null;
        List<MedicationCatalogItem> items = new ArrayList<>();
        UUID linked = rx != null ? rx.getMedicationCatalogItemId() : null;
        if (catalogue != null && linked != null && hospitalId != null) {
            catalogue.findByIdAndHospital_Id(linked, hospitalId).ifPresent(items::add);
        }
        for (MedicationCatalogItem item : itemsFor(medicationName, null, activeCatalogue(hospitalId))) {
            if (items.stream().noneMatch(i -> sameItem(i, item))) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * The catalogue items the check reads the drug through: the order's linked item, and every active
     * item its name names (the prescribing check's rule, QA D1223).
     */
    public static List<MedicationCatalogItem> itemsFor(String medicationName, UUID linkedItemId,
                                                       List<MedicationCatalogItem> active) {
        List<MedicationCatalogItem> items = new ArrayList<>();
        if (active == null) {
            return items;
        }
        for (MedicationCatalogItem item : active) {
            if (item == null) {
                continue;
            }
            boolean linked = linkedItemId != null && linkedItemId.equals(item.getId());
            if (linked || (item.isActive() && medicationName != null && !medicationName.isBlank()
                    && DispenseVerificationService.namesCatalogItem(medicationName, item))) {
                items.add(item);
            }
        }
        return items;
    }

    private static boolean sameItem(MedicationCatalogItem a, MedicationCatalogItem b) {
        return a == b || (a.getId() != null && a.getId().equals(b.getId()));
    }

    /* -- The rule ------------------------------------------------------------------------------ */

    /**
     * The stop this drug meets with these allergies, or empty: the most severe active allergy that
     * covers it, else the safety net's class allergy when the name resolves to no ingredient.
     */
    public static Optional<Stop> stop(UUID orderId, String medicationName, List<MedicationCatalogItem> items,
                                      List<PatientAllergy> allergies) {
        if (medicationName == null || medicationName.isBlank() || allergies == null || allergies.isEmpty()) {
            return Optional.empty();
        }
        AllergyDrugIdentity identity = AllergyDrugIdentity.of(medicationName, items);
        Optional<PatientAllergy> worst = identity.matchingAllergies(allergies).stream()
            .max(Comparator.comparingInt(PrescriptionServiceImpl::allergySeverityRank));
        if (worst.isPresent()) {
            return Optional.of(bind(orderId, medicationName, items, documentedCard(worst.get(), medicationName), worst.get(), "documented", allergies));
        }
        return identity.unverifiableClassAllergy(allergies)
            .map(allergy -> bind(orderId, medicationName, items, unverifiableCard(medicationName, allergy), allergy, "unverifiable", allergies));
    }

    /**
     * Every card a gate shows for this drug: the stop first (with the token its acknowledgement
     * sends back), then the cross-reactivity advisory. Empty when there is nothing to say.
     */
    public static List<CdsCard> cards(UUID orderId, String medicationName, List<MedicationCatalogItem> items,
                                      List<PatientAllergy> allergies) {
        List<CdsCard> cards = new ArrayList<>();
        stop(orderId, medicationName, items, allergies).ifPresent(s -> cards.add(s.card()));
        CrossReactivityAdvisory.card(medicationName, allergies).ifPresent(cards::add);
        return cards;
    }

    /** {@link #cards(UUID, String, List, List)} for one order, its allergies read now. */
    public List<CdsCard> cardsFor(Prescription rx, String medicationName) {
        List<PatientAllergy> current = currentAllergies(rx);
        if (rx == null || current.isEmpty()) {
            return List.of();
        }
        return cards(rx.getId(), medicationName, itemsFor(rx, medicationName), current);
    }

    /* -- At a gate ----------------------------------------------------------------------------- */

    /**
     * The re-check every gate calls before it writes: a drug the patient's current allergies stop
     * passes only with the clinician's acknowledgement of that allergy and a reason.
     *
     * @return the acknowledgement to record and audit, or {@code null} when nothing is to acknowledge
     * @throws CdsCriticalBlockException on {@link #ACK_FIELD}, with the stop's card, when the reason is
     *         missing or answers another allergy state
     */
    public Acknowledgement requireAcknowledged(Gate gate, Prescription rx, String medicationName,
                                               String reason, String token) {
        List<PatientAllergy> current = currentAllergies(rx);
        if (current.isEmpty()) {
            return null;
        }
        Optional<Stop> stop = stop(rx != null ? rx.getId() : null, medicationName,
            itemsFor(rx, medicationName), current);
        if (stop.isEmpty()) {
            return null;
        }
        String given = reason == null ? "" : reason.trim();
        if (given.isEmpty()) {
            throw new CdsCriticalBlockException(
                MessageUtil.resolveOrRaw(gate.requiredKey(), stop.get().card().summary(), stop.get().allergen()),
                List.of(stop.get().card()), ACK_FIELD);
        }
        if (!stop.get().token().equals(stripPrefix(token))) {
            throw new CdsCriticalBlockException(
                MessageUtil.resolveOrRaw("allergy.recheck.acknowledgementStale", stop.get().allergen()),
                List.of(stop.get().card()), ACK_FIELD);
        }
        return new Acknowledgement(gate, stop.get(), given);
    }

    /** The line the record keeps (a dose's or a fill's note): the allergy acknowledged and the reason. */
    public static String recordLine(Acknowledgement ack) {
        return MessageUtil.resolveOrRaw("allergy.recheck.recordLine", ack.stop().allergen(), ack.reason());
    }

    /** Keep both the note and acknowledgement intact; refuse overflow instead of losing clinical text. */
    public static String withRecordLine(String note, Acknowledgement ack, int max) {
        if (ack == null) {
            return note;
        }
        String line = recordLine(ack);
        // Neither the acknowledgement nor the clinical note may be silently truncated.
        String joined = note == null || note.isBlank() ? line : line + "\n" + note;
        if (joined.length() > max) {
            throw new com.example.hms.exception.FieldBusinessException(ACK_FIELD,
                MessageUtil.resolveOrRaw("allergy.recheck.noteTooLong", max));
        }
        return joined;
    }

    /**
     * After commit: who acknowledged, the allergy, the rule, a digest of the reason, the gate and the
     * time. Ids and a digest only, as the prescriber's override audit: the audit row is not encrypted,
     * the reason is kept on the record the gate wrote.
     */
    public void auditAfterCommit(Acknowledgement ack, UUID actorUserId, UUID hospitalId, UUID patientId,
                                 UUID resourceId) {
        if (ack == null || audit == null) {
            return;
        }
        UUID allergyId = ack.stop().allergy().getId();
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);
        String details = "{\"gate\":\"" + ack.gate().name() + "\",\"reasonDigest\":\"" + digest(ack.reason())
            + "\",\"warning\":\"" + ack.stop().kind()
            + "\",\"allergyId\":" + (allergyId == null ? "null" : "\"" + allergyId + "\"")
            + ",\"acknowledgementToken\":\"" + ack.stop().token() + "\",\"acknowledgedAt\":\"" + at + "\"}";
        TransactionCallbacks.afterCommit(() -> {
            try {
                audit.logEvent(AuditEventRequestDTO.builder()
                    .eventType(ack.gate().auditType)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorUserId)
                    .hospitalId(hospitalId)
                    .patientId(patientId)
                    .eventDescription("Allergy acknowledged with a reason")
                    .details(details)
                    .entityType(ack.gate().entityType)
                    .resourceId(resourceId != null ? resourceId.toString() : null)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Allergy acknowledgement audit not written for {}: {}", resourceId,
                    ex.getClass().getSimpleName());
            }
        });
    }

    /* -- Cards and the token ------------------------------------------------------------------- */

    private static Stop bind(UUID orderId, String medicationName, List<MedicationCatalogItem> items,
                             CdsCard card, PatientAllergy allergy, String kind,
                             List<PatientAllergy> allergies) {
        // Bind the whole active allergy state, not just the most severe displayed row.
        // A second allergy may be added or edited while that row remains the same.
        String state = allergies.stream().filter(Objects::nonNull).filter(PatientAllergy::isActive)
            .map(a -> digest(key(kind, a))).sorted().collect(java.util.stream.Collectors.joining("|"));
        String drug = items == null ? "" : items.stream().filter(Objects::nonNull)
            .map(item -> digest(String.join("|", String.valueOf(item.getId()),
                String.valueOf(item.getGenericName()), String.valueOf(item.getBrandName()),
                String.valueOf(item.getNameFr()), String.valueOf(item.getAtcCode()))))
            .sorted().collect(java.util.stream.Collectors.joining("|"));
        String token = digest(orderId + "|" + medicationName + "|" + drug + "|" + key(kind, allergy) + "|" + state);
        return new Stop(card.withExceptionKey(KEY_PREFIX + token), allergy, kind, token);
    }

    /**
     * Every field the warning rests on, with the row's last update (the review of #1104, P1): an edit
     * of the same allergy (MILD to SEVERE, another reaction) voids an acknowledgement given before.
     */
    private static String key(String kind, PatientAllergy allergy) {
        Object identity = allergy.getId() != null ? allergy.getId() : AllergyDrugIdentity.displayOf(allergy);
        return String.join("|", kind, String.valueOf(identity),
            String.valueOf(allergy.getSeverity()), String.valueOf(allergy.getAllergenCode()),
            String.valueOf(allergy.getAllergenDisplay()), String.valueOf(allergy.getCategory()),
            String.valueOf(allergy.getReaction()), String.valueOf(allergy.getReactionNotes()),
            String.valueOf(allergy.getVerificationStatus()), String.valueOf(allergy.isActive()),
            String.valueOf(allergy.getUpdatedAt()));
    }

    private static String stripPrefix(String token) {
        if (token == null) {
            return "";
        }
        String t = token.trim();
        return t.startsWith(KEY_PREFIX) ? t.substring(KEY_PREFIX.length()) : t;
    }

    private static CdsCard documentedCard(PatientAllergy allergy, String medicationName) {
        String severity = MessageUtil.resolveOrRaw("cds.severity."
            + (allergy.getSeverity() != null ? allergy.getSeverity().name() : "UNKNOWN"));
        return new CdsCard(
            MessageUtil.resolveOrRaw("allergy.recheck.documented", medicationName,
                AllergyDrugIdentity.displayOf(allergy), severity),
            MessageUtil.resolveOrRaw("allergy.recheck.detail"),
            CdsCard.Indicator.CRITICAL,
            new Source(CrossReactivityAdvisory.sourceLabel(), null, null),
            null, null, null, UUID.randomUUID().toString());
    }

    private static CdsCard unverifiableCard(String medicationName, PatientAllergy allergy) {
        return new CdsCard(
            MessageUtil.resolveOrRaw("allergy.recheck.unverifiable", medicationName,
                AllergyDrugIdentity.displayOf(allergy)),
            MessageUtil.resolveOrRaw("allergy.recheck.detail"),
            CdsCard.Indicator.CRITICAL,
            new Source(CrossReactivityAdvisory.sourceLabel(), null, null),
            null, null, null, UUID.randomUUID().toString());
    }

    private static String digest(String payload) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(Objects.toString(payload, "").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
