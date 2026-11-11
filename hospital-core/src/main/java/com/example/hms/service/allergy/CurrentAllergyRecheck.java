package com.example.hms.service.allergy;

import com.example.hms.cdshooks.CdsCriticalBlockException;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.enums.AllergyReviewDecision;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.PrescriptionServiceImpl;
import com.example.hms.service.i18n.NotificationLocales;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
 * <p>Policy 4 (user recommendation 2026-11-10, pending clinical approval; it replaces the
 * provisional "acknowledge with a reason" default of #1108): a stop (a direct or class match, or the
 * safety net) holds the gate's write until an AUTHORIZED PRESCRIBER reviews the conflict on the order
 * ({@code PrescriptionService#reviewAllergyConflict}). A nurse's or a pharmacist's own reason never
 * releases it: no gate reads one. The hold answers on {@link #HOLD_FIELD} with the allergy card and
 * who was told; the order's prescriber, signer and encounter clinician get one urgent in-basket notice
 * per order until a review is recorded. A CONTINUE review releases every gate while the conflict's
 * {@link #reviewToken} still equals the one reviewed (any change of the allergy state or of the
 * order's drug, dose, route or frequency voids it); a STOP review refuses every gate. Recording a
 * dose as HELD or REFUSED is never asked. No emergency bypass: none is approved.
 */
@Slf4j
@Component
public class CurrentAllergyRecheck {

    /**
     * The field every hold names, so a screen shows the hold card. Not a request field: nothing a
     * nurse or a pharmacist sends releases the hold (AllergyPrescriberReviewGuardTest).
     */
    public static final String HOLD_FIELD = "allergyPrescriberReview";

    /** The in-basket reference type of the prescriber's notice (its reference id is the order). */
    public static final String NOTICE_REFERENCE_TYPE = "PRESCRIPTION_ALLERGY_REVIEW";

    static final String NOTICE_TITLE_KEY = "allergy.review.notice.title";
    static final String NOTICE_BODY_KEY = "allergy.review.notice.body";

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
    }

    private final PatientAllergyRepository allergyRepository;
    private final MedicationCatalogItemRepository catalogue;
    private final AuditEventLogService audit;

    /** The prescriber's notice; absent in a pure-unit test. */
    private InBasketService notices;

    @Autowired(required = false)
    public void setNotices(InBasketService notices) {
        this.notices = notices;
    }

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

    /**
     * A gate passing under the prescriber's CONTINUE review of this very conflict: what the record and
     * the audit keep (the warning, the review's token, who reviewed it and when).
     */
    public record Acknowledgement(Gate gate, Stop stop, String reviewToken, UUID reviewerId,
                                  LocalDateTime reviewedAt) {
    }

    /** The conflict an order meets now: its stop and the token a prescriber's review answers. */
    public record Conflict(Stop stop, String reviewToken) {
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
     * The conflict this order meets now, read on its own drug, or empty: what the prescriber reviews.
     */
    public Optional<Conflict> currentConflict(Prescription rx) {
        return currentConflict(Objects.requireNonNull(rx, "rx"), rx.getMedicationName());
    }

    private Optional<Conflict> currentConflict(Prescription rx, String medicationName) {
        List<PatientAllergy> current = currentAllergies(rx);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        return stop(rx.getId(), medicationName, itemsFor(rx, medicationName), current)
            .map(s -> new Conflict(s, reviewToken(rx, s)));
    }

    /**
     * The re-check every gate calls before it writes (Policy 4): a drug the patient's current allergies
     * stop passes only under an authorized prescriber's CONTINUE review of this very conflict. It takes
     * no reason: nothing a nurse or a pharmacist writes releases the hold.
     *
     * @return the review the gate passes under, to record and audit, or {@code null} when nothing stops
     * @throws CdsCriticalBlockException on {@link #HOLD_FIELD}, with the stop's card, when no review of
     *         this conflict is on record (the prescriber is told) or the prescriber decided STOP
     */
    public Acknowledgement requirePrescriberReview(Gate gate, Prescription rx, String medicationName) {
        Optional<Conflict> conflict = rx == null ? Optional.empty() : currentConflict(rx, medicationName);
        if (conflict.isEmpty()) {
            return null;
        }
        Stop stop = conflict.get().stop();
        String token = conflict.get().reviewToken();
        boolean reviewed = token.equals(rx.getAllergyReviewToken()) && rx.getAllergyReviewDecision() != null;
        if (reviewed && rx.getAllergyReviewDecision() == AllergyReviewDecision.STOP) {
            throw new CdsCriticalBlockException(
                MessageUtil.resolveOrRaw("allergy.recheck.prescriberStopped", stop.allergen()),
                List.of(stop.card()), HOLD_FIELD);
        }
        if (reviewed) {
            User reviewer = rx.getAllergyReviewedBy();
            return new Acknowledgement(gate, stop, token, reviewer != null ? reviewer.getId() : null,
                rx.getAllergyReviewedAt());
        }
        List<String> told = notifyPrescribers(rx, stop);
        String why = told.isEmpty()
            ? MessageUtil.resolveOrRaw("allergy.recheck.awaitingReviewNobody", stop.card().summary())
            : MessageUtil.resolveOrRaw("allergy.recheck.awaitingReview", stop.card().summary(),
                String.join(", ", told));
        throw new CdsCriticalBlockException(why, List.of(stop.card()), HOLD_FIELD);
    }

    /**
     * The token a prescriber's review answers: the stop's token (the order, its drug and the whole
     * active allergy state) with what the order asks for (dose, unit, route, frequency, PRN, duration).
     * Any of them changed after the review voids it.
     */
    public static String reviewToken(Prescription rx, Stop stop) {
        // A digest's input, never text a reader sees: the route is the order's own code.
        List<Object> ordered = Arrays.asList(rx.getMedicationCatalogItemId(), rx.getMedicationCode(),
            rx.getDosage(), rx.getDoseUnit(), rx.getRoute(), rx.getFrequency(),
            com.example.hms.service.impl.PrnMinInterval.isAsNeeded(rx), rx.getPrnMinIntervalHours(),
            rx.getDuration());
        String order = ordered.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("|"));
        return digest(stop.token() + "|" + order);
    }

    /** The token sent back by a screen, without the card's prefix. */
    public static String stripPrefix(String token) {
        if (token == null) {
            return "";
        }
        String t = token.trim();
        return t.startsWith(KEY_PREFIX) ? t.substring(KEY_PREFIX.length()) : t;
    }

    /**
     * Who must review: the order's prescriber, its signer and the encounter's clinician, each told once
     * per order by an urgent in-basket notice (the in-basket keeps one item per order and recipient; a
     * recorded review clears them, so a later conflict is told again). Filed in a transaction of its own:
     * the hold rolls the gate's back. Returns the names shown on the hold.
     */
    private List<String> notifyPrescribers(Prescription rx, Stop stop) {
        InBasketService service = notices;
        UUID hospitalId = rx.getHospital() != null ? rx.getHospital().getId() : null;
        List<String> reached = new ArrayList<>();
        if (service == null || hospitalId == null) {
            return reached;
        }
        // Review of #1110 (P2): only those actually told (filed, or already holding the notice) are named;
        // a failed notice never reads as delivered, and the others are still tried.
        reviewersOf(rx).forEach((recipient, name) -> {
            if (fileNotice(service, noticeFor(rx, stop, recipient, hospitalId), rx) && !name.isBlank()) {
                reached.add(name);
            }
        });
        return reached;
    }

    /** True when the notice was filed, or the in-basket already held it (it answers null for a duplicate). */
    private static boolean fileNotice(InBasketService service, CreateInBasketItemRequest item, Prescription rx) {
        try {
            item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, NOTICE_TITLE_KEY, item.getTitleArgs()));
            item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, NOTICE_BODY_KEY, item.getBodyArgs()));
            service.createItemInNewTransaction(item);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Allergy review notice not filed for prescription {}: {}", rx.getId(),
                ex.getClass().getSimpleName());
            return false;
        }
    }

    /** The order's prescriber, its signer and the encounter's clinician, once each, with their names. */
    private static Map<UUID, String> reviewersOf(Prescription rx) {
        Map<UUID, String> recipients = new LinkedHashMap<>();
        for (Staff staff : Arrays.asList(rx.getStaff(), rx.getSignedBy(),
                rx.getEncounter() != null ? rx.getEncounter().getStaff() : null)) {
            if (staff != null && staff.getUser() != null && staff.getUser().getId() != null) {
                recipients.putIfAbsent(staff.getUser().getId(), Objects.toString(staff.getFullName(), ""));
            }
        }
        return recipients;
    }

    private static CreateInBasketItemRequest noticeFor(Prescription rx, Stop stop, UUID recipient, UUID hospitalId) {
        String patientName = rx.getPatient() != null ? rx.getPatient().getFullName() : null;
        CreateInBasketItemRequest item = new CreateInBasketItemRequest();
        item.setRecipientUserId(recipient);
        item.setHospitalId(hospitalId);
        item.setItemType(InBasketItemType.TASK);
        item.setPriority(InBasketPriority.URGENT);
        item.setReferenceId(rx.getId());
        item.setReferenceType(NOTICE_REFERENCE_TYPE);
        item.setEncounterId(rx.getEncounter() != null ? rx.getEncounter().getId() : null);
        item.setPatientId(rx.getPatient() != null ? rx.getPatient().getId() : null);
        item.setPatientName(patientName);
        item.setTitleKey(NOTICE_TITLE_KEY);
        item.setBodyKey(NOTICE_BODY_KEY);
        item.setTitleArgs(new Object[] {rx.getMedicationName()});
        item.setBodyArgs(new Object[] {rx.getMedicationName(), patientName, stop.allergen()});
        return item;
    }

    /** The line the record keeps (a dose's or a fill's note): the allergy and the review it passed under. */
    public static String recordLine(Acknowledgement ack) {
        return MessageUtil.resolveOrRaw("allergy.recheck.reviewedLine", ack.stop().allergen(),
            ack.reviewedAt() != null ? ack.reviewedAt().withNano(0).toString().replace('T', ' ') : "");
    }

    /** Keep both the note and the review line intact; refuse overflow instead of losing clinical text. */
    public static String withRecordLine(String note, Acknowledgement ack, int max, String noteField) {
        if (ack == null) {
            return note;
        }
        String line = recordLine(ack);
        // Neither the review line nor the clinical note may be silently truncated.
        String joined = note == null || note.isBlank() ? line : line + "\n" + note;
        if (joined.length() > max) {
            // A combined record-length error, not an allergy hold: naming HOLD_FIELD would show a hold card.
            throw new com.example.hms.exception.FieldBusinessException(noteField,
                MessageUtil.resolveOrRaw("allergy.recheck.noteTooLong", max));
        }
        return joined;
    }

    /**
     * After commit: who passed the gate, the allergy, the rule, the gate, and the prescriber's review it
     * passed under (reviewer, time, token). Ids only: the audit row is not encrypted.
     */
    public void auditAfterCommit(Acknowledgement ack, UUID actorUserId, UUID hospitalId, UUID patientId,
                                 UUID resourceId) {
        if (ack == null || audit == null) {
            return;
        }
        UUID allergyId = ack.stop().allergy().getId();
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);
        String details = "{\"gate\":\"" + ack.gate().name() + "\",\"warning\":\"" + ack.stop().kind()
            + "\",\"allergyId\":" + (allergyId == null ? "null" : "\"" + allergyId + "\"")
            + ",\"reviewToken\":\"" + ack.reviewToken() + "\",\"reviewedBy\":"
            + (ack.reviewerId() == null ? "null" : "\"" + ack.reviewerId() + "\"")
            + ",\"reviewedAt\":" + (ack.reviewedAt() == null ? "null" : "\"" + ack.reviewedAt() + "\"")
            + ",\"passedAt\":\"" + at + "\"}";
        TransactionCallbacks.afterCommit(() -> {
            try {
                audit.logEvent(AuditEventRequestDTO.builder()
                    .eventType(ack.gate().auditType)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorUserId)
                    .hospitalId(hospitalId)
                    .patientId(patientId)
                    .eventDescription("Allergy conflict passed under the prescriber's review")
                    .details(details)
                    .entityType(ack.gate().entityType)
                    .resourceId(resourceId != null ? resourceId.toString() : null)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Allergy review pass audit not written for {}: {}", resourceId,
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
