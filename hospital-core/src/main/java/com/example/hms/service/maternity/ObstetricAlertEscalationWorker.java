package com.example.hms.service.maternity;

import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UrgentAlertIncident;
import com.example.hms.model.User;
import com.example.hms.model.labor.ObstetricAlertEscalation;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.repository.ObstetricAlertEscalationRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.NotificationService;
import com.example.hms.service.escalation.UrgentAlertNotDeliveredException;
import com.example.hms.service.escalation.UrgentEscalationSupport;
import com.example.hms.service.lab.CriticalResultEscalationLadder;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.NotificationLocalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D605: the transactional half of the obstetric alert escalation. Each
 * call runs in its own transaction, so one alert's failure never rolls back
 * another's, nor the clinical record that raised it.
 *
 * <p>Who hears about an alert:
 * <ul>
 *   <li><b>First notice</b>: the doctors and midwives on call at the alert's
 *       hospital now; when none of them is on the rota (nobody, or only a lab
 *       technician or pharmacist), every doctor and midwife of the hospital.</li>
 *   <li><b>Each reminder</b>: the same circle, plus every doctor, midwife and
 *       nurse of the hospital. Hospital administrators are deliberately left
 *       out: they are off the chart and the in-basket (decision D5), and every
 *       message here carries the patient's name and clinical text.</li>
 * </ul>
 * Recipients always come from the alert's own hospital, never the caller's.
 * The clinician who documented the alert is told separately by the module
 * that raised it, and an acknowledgement counts only from somebody else.
 *
 * <p>Scorecard K4 (V386): the reminders are the steps of the one escalation ladder
 * ({@link UrgentEscalationSupport}): the covering clinician is added, then the médecin-chef, then the alert is
 * « non résolue » (an incident, and a flag with no clinical text for the administrators), then reminders that
 * slow down and never stop. No count of rounds ends it. The sources that are not obstetric
 * ({@link ObstetricAlertEscalationService#GENERAL_SOURCES}) have the people their module names as their circle,
 * never the obstetric team. The first notice is owed from the moment the row commits: {@link #deliverFirstNotice}
 * claims it, and a delivery that fails gives the claim back to the sweep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObstetricAlertEscalationWorker {

    public static final String REFERENCE_TYPE = "OBSTETRIC_ALERT";
    public static final String NOTIFICATION_TYPE = "OBSTETRIC_ALERT";
    public static final String ESCALATION_TYPE = "OBSTETRIC_ALERT_ESCALATION";
    /**
     * QA D3862: an adult's NEWS2 is not an obstetric alert; its bell and its reminders carry their own types, so the
     * notification list never labels it « Alerte obstétricale urgente ».
     */
    public static final String EARLY_WARNING_NOTIFICATION_TYPE = "EARLY_WARNING_ALERT";
    public static final String EARLY_WARNING_ESCALATION_TYPE = "EARLY_WARNING_ALERT_ESCALATION";

    /** The physician of the first circle; policy rule 2: a newborn's danger sign always reaches one. */
    private static final String NEWBORN_PHYSICIAN_ROLE = "ROLE_DOCTOR";
    private static final String MIDWIFE_ROLE = "ROLE_MIDWIFE";
    private static final List<String> FIRST_NOTICE_ROLES = List.of(NEWBORN_PHYSICIAN_ROLE, MIDWIFE_ROLE);
    private static final String NURSE_ROLE = "ROLE_NURSE";
    private static final List<String> REMINDER_ROLES = List.of(NEWBORN_PHYSICIAN_ROLE, MIDWIFE_ROLE, NURSE_ROLE);
    private static final int TITLE_MAX = 500;
    static final String REMINDER_KEY = "obstetric.alert.escalation";
    /**
     * QA D2582 (r65): the documenter's reminder. She cannot acknowledge her own alert, so no in-basket
     * item is filed for her; the team's reminder told her to « accusez réception dans votre boîte de
     * réception » and sent her to an empty page. Hers says the team has not acknowledged it yet.
     */
    static final String DOCUMENTER_REMINDER_KEY = "obstetric.alert.escalation.documenter";
    /**
     * Scorecard K4: the notice of the last step of the ladder and of every reminder after it: {0} the alert type,
     * {1} the patient, {2} the alert sentence, {3} the minutes since the first notice.
     */
    static final String UNRESOLVED_KEY = "urgent.alert.unresolved";
    /**
     * Scorecard K4: the bell of an urgent alert that is neither obstetric nor an early-warning score (a severe
     * transfusion reaction, a positive culture, an adult's urgent hypertension, an overdue countersignature).
     */
    public static final String URGENT_NOTIFICATION_TYPE = "URGENT_CLINICAL_ALERT";
    public static final String URGENT_ESCALATION_TYPE = "URGENT_CLINICAL_ALERT_ESCALATION";
    /** Scorecard K4: the laboratory director's task after a severe transfusion reaction, filed with the first notice. */
    public static final String HAEMOVIGILANCE_REFERENCE_TYPE = "TRANSFUSION_REACTION_HAEMOVIGILANCE";
    static final String HAEMOVIGILANCE_TITLE_KEY = "transfusion.reaction.haemovigilance.inBasketTitle";
    static final String HAEMOVIGILANCE_BODY_KEY = "transfusion.reaction.haemovigilance.inBasketBody";
    private static final String LAB_DIRECTOR_ROLE = "ROLE_LAB_DIRECTOR";
    /**
     * The roles a step of the ladder may add to any alert: the covering clinician and the médecin-chef are doctors
     * ({@code CriticalResultRecipients.DOCTOR_ROLES}); a transfusion alert and its haemovigilance task also reach
     * the laboratory director. The in-basket guards read this list with the two above: every role an item is
     * filed for can open the in-basket and acknowledge.
     */
    static final List<String> LADDER_ROLES =
        List.of(NEWBORN_PHYSICIAN_ROLE, "ROLE_PHYSICIAN", "ROLE_SURGEON", LAB_DIRECTOR_ROLE);
    private static final String PATIENT_FALLBACK = "patient.fallback.generic";

    private final ObstetricAlertEscalationRepository escalationRepository;
    private final InBasketItemRepository inBasketItemRepository;
    private final OnCallScheduleRepository onCallScheduleRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final NotificationService notificationService;
    private final InBasketService inBasketService;
    private final MessageSource messageSource;
    private final Clock clock;
    private final ObstetricAlertCurrency currency;

    /**
     * Scorecard K4: the one ladder (setter-injected). It is a component of the application, so it is always there
     * in production; a persistence slice or a unit test of the circles runs without it, on the default minutes
     * and with no senior step.
     */
    private UrgentEscalationSupport ladder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLadder(UrgentEscalationSupport ladder) {
        this.ladder = ladder;
    }

    /**
     * QA D3862: who stands for a patient's adult early warning (setter-injected; absent, the hospital's doctors and
     * nurses stand in). The patient's care team ({@code News2CareTeam}: the ward's nurses, the responsible and on-call
     * doctors), and the critical-result ladder's covering doctor and nurse in charge when the ward names nobody.
     */
    private com.example.hms.service.lab.CriticalResultRecipients clinicalRecipients;
    private com.example.hms.service.vitals.News2CareTeam careTeam;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setClinicalRecipients(com.example.hms.service.lab.CriticalResultRecipients clinicalRecipients) {
        this.clinicalRecipients = clinicalRecipients;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCareTeam(com.example.hms.service.vitals.News2CareTeam careTeam) {
        this.careTeam = careTeam;
    }

    /**
     * Scorecard K4: writes the row of one URGENT alert in the CALLER's transaction, so it commits with the clinical
     * record that raised it. Until its first notice is delivered the row is the obligation
     * ({@code firstNoticeFiledAt} is null).
     *
     * @param firstNoticeDelivered the raising module delivered the first notice itself, in this transaction
     * @return the row's id, or null when an alert this one repeats is still escalating
     */
    public UUID file(ObstetricAlertEscalationService.UrgentAlert alert, boolean firstNoticeDelivered) {
        if (stillEscalating(alert)) {
            // QA D3862: the alert this reading repeats is still reminding; a second item would only bury it.
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return escalationRepository.save(rowOf(alert, now, firstNoticeDelivered ? now : null)).getId();
    }

    private static ObstetricAlertEscalation rowOf(ObstetricAlertEscalationService.UrgentAlert alert, LocalDateTime now,
                                                  LocalDateTime firstNoticeFiledAt) {
        return ObstetricAlertEscalation.builder()
            .hospitalId(alert.hospitalId())
            .patientId(alert.patientId())
            .sourceType(alert.sourceType())
            .sourceId(alert.sourceId())
            .alertType(alert.alertType())
            .typeLabel(alert.typeLabel())
            .alertMessage(alert.alertMessage())
            .alertMessageKey(alert.alertMessageKey())
            .alertMessageArgs(alert.alertMessageArgs())
            .documentedByUserId(alert.documentedByUserId())
            .lastEscalationAt(now)
            .firstNoticeFiledAt(firstNoticeFiledAt)
            .namedRecipients(alert.namedRecipients().isEmpty() ? null : List.copyOf(alert.namedRecipients()))
            .alreadyRang(alert.alreadyNotified().isEmpty() ? null : List.copyOf(alert.alreadyNotified()))
            .build();
    }

    /**
     * The row and the first notice of one URGENT alert in one transaction of its own, for a caller with no
     * clinical transaction to write the row in.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID raise(ObstetricAlertEscalationService.UrgentAlert alert) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (stillEscalating(alert)) {
            return null;
        }
        ObstetricAlertEscalation row = escalationRepository.save(rowOf(alert, now, now));
        sendFirstNotice(row, alert, now);
        return row.getId();
    }

    /**
     * Scorecard K4: delivers the first notice a row still owes, in a transaction of its own. The row is claimed
     * first, so the delivery that follows the commit and the sweep never both deliver; when nothing could be
     * delivered the exception undoes the claim and the row stays owed.
     *
     * @param alert the alert as its module raised it; null in the sweep, which reads it back from the row
     * @return true when the notice was delivered now
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deliverFirstNotice(UUID escalationId, ObstetricAlertEscalationService.UrgentAlert alert) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (escalationRepository.claimFirstNotice(escalationId, now) != 1) {
            return false;
        }
        ObstetricAlertEscalation row = escalationRepository.findById(escalationId).orElse(null);
        if (row == null) {
            return false;
        }
        if (currency.noLongerUrgent(row)) {
            // Review of #1370 (F2): the record no longer supports the alert; nothing is sent.
            closeNoLongerUrgent(row, now);
            return false;
        }
        sendFirstNotice(row, alert != null ? alert : alertOf(row), now);
        return true;
    }

    /** The alert of a row whose first notice the sweep delivers: what the row holds, and the patient's name. */
    private ObstetricAlertEscalationService.UrgentAlert alertOf(ObstetricAlertEscalation row) {
        Locale locale = NotificationLocales.STAFF;
        String patientName = patientRepository.findByIdUnscoped(row.getPatientId())
            .map(Patient::getFullName).filter(n -> !n.isBlank())
            .orElseGet(() -> messageSource.getMessage(PATIENT_FALLBACK, null, locale));
        String title = messageSource.getMessage(firstNoticeKey(row.getSourceType()), new Object[]{
            row.getTypeLabel() == null ? "" : row.getTypeLabel(), patientName,
            row.getAlertMessage() == null ? "" : row.getAlertMessage()}, locale);
        return new ObstetricAlertEscalationService.UrgentAlert(row.getHospitalId(), row.getPatientId(), patientName,
            row.getSourceType(), row.getSourceId(), row.getAlertType(), row.getTypeLabel(), row.getAlertMessage(),
            row.getDocumentedByUserId(), title, row.getAlertMessageKey(), row.getAlertMessageArgs(),
            row.getAlreadyRang() == null ? null : new LinkedHashSet<>(row.getAlreadyRang()),
            row.getNamedRecipients() == null ? null : new LinkedHashSet<>(row.getNamedRecipients()));
    }

    private void sendFirstNotice(ObstetricAlertEscalation row, ObstetricAlertEscalationService.UrgentAlert alert,
                                 LocalDateTime now) {
        closeSupersededStillbirthEscalations(alert, row.getId(), now);
        Set<String> recipients = firstCircle(alert);
        // QA D665: each reader gets the notice in her own language; the stored
        // staff-locale text only titles the in-basket item.
        Object[] args = {typeLabelArg(alert.sourceType(), alert.alertType(), alert.typeLabel()),
            alert.patientName() == null || alert.patientName().isBlank()
                ? NotificationLocalizer.key(PATIENT_FALLBACK) : alert.patientName(),
            alertMessageArg(alert.alertMessageKey(), alert.alertMessageArgs(), alert.alertMessage())};
        // QA D3304: the maternal early-warning page already rang its circle for this reading; its first notice
        // here is the item to acknowledge only, never a second bell. Its reminders ring like every other alert's.
        // QA D3862: a vital set's NEWS2 or newborn page rang part of the circle; only the others get a bell.
        boolean alreadyRang = ObstetricAlertEscalationService.SOURCE_VITAL_SIGN.equals(alert.sourceType());
        // The laboratory's task first: it is filed in this transaction, and a failure there must find nothing
        // of this notice already out.
        Delivery task = ObstetricAlertEscalationService.SOURCE_TRANSFUSION_REACTION.equals(alert.sourceType())
            ? fileHaemovigilanceTask(row, alert.patientName()) : Delivery.NONE;
        Delivery delivery = task.plus(deliver(row, firstNoticeKey(alert.sourceType()), args, alert.notificationText(),
            alert.patientName(), recipients, alreadyRang ? null : noticeType(alert.sourceType(), false),
            alert.alreadyNotified()));
        if (delivery.nothingDelivered()) {
            // The exception undoes the claim of the first notice: the row stays owed and the sweep delivers it.
            throw new UrgentAlertNotDeliveredException();
        }
        if (delivery.reached() == 0) {
            log.warn("Urgent alert {} at hospital {} has nobody in its first circle to notify; the ladder widens it",
                row.getId(), alert.hospitalId());
        }
    }

    /**
     * Scorecard K4: the haemovigilance task of a severe transfusion reaction, for the laboratory directors; with
     * none, for the médecin-chef. Filed in the transaction of the first notice: a task that cannot be filed leaves
     * the row owed with it.
     */
    private Delivery fileHaemovigilanceTask(ObstetricAlertEscalation row, String patientName) {
        Set<String> readers = new LinkedHashSet<>(
            staffRepository.findActiveUsernamesByHospitalAndRole(row.getHospitalId(), LAB_DIRECTOR_ROLE));
        if (readers.isEmpty() && ladder != null) {
            readers.addAll(ladder.medicalLead(row.getHospitalId()));
        }
        if (readers.isEmpty()) {
            log.error("Severe transfusion reaction alert {} at hospital {} has no laboratory director and no "
                + "médecin-chef for its haemovigilance task", row.getId(), row.getHospitalId());
            return Delivery.NONE;
        }
        Locale locale = NotificationLocales.STAFF;
        Object[] titleArgs = {patientName == null ? "" : patientName};
        List<String> lower = readers.stream().map(u -> u.toLowerCase(Locale.ROOT)).toList();
        int filed = 0;
        for (User user : userRepository.findByUsernameInIgnoreCase(lower)) {
            CreateInBasketItemRequest task = CreateInBasketItemRequest.builder()
                .recipientUserId(user.getId())
                .hospitalId(row.getHospitalId())
                .itemType(InBasketItemType.TASK)
                .priority(InBasketPriority.URGENT)
                .title(messageSource.getMessage(HAEMOVIGILANCE_TITLE_KEY, titleArgs, locale))
                .titleArgs(titleArgs)
                .body(messageSource.getMessage(HAEMOVIGILANCE_BODY_KEY, titleArgs, locale))
                .bodyArgs(titleArgs)
                .referenceId(row.getSourceId())
                .referenceType(HAEMOVIGILANCE_REFERENCE_TYPE)
                .patientId(row.getPatientId())
                .patientName(patientName)
                .build();
            // The keys travel with the item: each reader's copy is rendered in her language.
            task.setTitleKey(HAEMOVIGILANCE_TITLE_KEY);
            task.setBodyKey(HAEMOVIGILANCE_BODY_KEY);
            inBasketService.createItem(task);
            filed++;
        }
        return new Delivery(0, filed, 0);
    }

    /**
     * What a delivery did: the people other than the documenter it was for, the notices and items that went out,
     * and those that failed. A delivery with failures and nothing out is given back to the sweep.
     */
    record Delivery(int reached, int delivered, int failed) {
        static final Delivery NONE = new Delivery(0, 0, 0);

        Delivery plus(Delivery other) {
            return new Delivery(reached + other.reached, delivered + other.delivered, failed + other.failed);
        }

        boolean nothingDelivered() {
            return delivered == 0 && failed > 0;
        }
    }

    /**
     * QA D1801: a delivery's stillbirth alert is one alert. When a correction withdrew it and a later correction
     * raised it again before the sweep closed the first escalation, that first row would escalate beside the new
     * one. The new row replaces it: the old one is closed by the record (empty acknowledgedBy), like a withdrawn alert.
     */
    private void closeSupersededStillbirthEscalations(ObstetricAlertEscalationService.UrgentAlert alert,
                                                      UUID replacementId, LocalDateTime now) {
        if (!ObstetricAlertEscalationService.SOURCE_DELIVERY.equals(alert.sourceType())
            || !ObstetricAlertCurrency.STILLBIRTH_ALERT_MESSAGE_KEY.equals(alert.alertMessageKey())) {
            return;
        }
        for (ObstetricAlertEscalation open : escalationRepository.findOpenBySourceAndMessageKey(
                alert.hospitalId(), alert.sourceType(), alert.sourceId(), alert.alertMessageKey())) {
            if (Objects.equals(open.getId(), replacementId)) {
                continue;
            }
            closeIncident(open, UrgentAlertIncident.RESOLVED_NO_LONGER_URGENT, null, now);
            open.setAcknowledgedAt(now);
            open.setAcknowledgedBy(null);
            escalationRepository.save(open);
            // Its open CRITICAL tasks go too: acknowledging one could not stop the replacement's reminders.
            inBasketItemRepository.deleteOpenByReference(open.getId(), REFERENCE_TYPE);
        }
    }

    /**
     * One pass over one alert, after re-reading its current state: an owed first notice is delivered; an alert
     * acknowledged (here or through its in-basket items) is closed; then the step of the ladder that is due is
     * taken, unless the clinical record no longer supports the alert ({@link ObstetricAlertCurrency}): it is then
     * closed with an empty acknowledgedBy. No count of steps ends an alert (scorecard K4).
     *
     * @return true when a notice or a step was sent
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean escalateOne(UUID escalationId) {
        Optional<ObstetricAlertEscalation> found = escalationRepository.findById(escalationId);
        if (found.isEmpty()) {
            return false;
        }
        ObstetricAlertEscalation row = found.get();
        if (row.getAcknowledgedAt() != null || row.isLadderBacklog()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (row.getFirstNoticeFiledAt() == null) {
            // Owed: the delivery that followed the commit failed, or never ran.
            if (escalationRepository.claimFirstNotice(row.getId(), now) != 1) {
                return false;
            }
            ObstetricAlertEscalation owed = escalationRepository.findById(escalationId).orElse(null);
            if (owed == null) {
                return false;
            }
            if (currency.noLongerUrgent(owed)) {
                // Review of #1370 (F2): the record changed while the notice was owed (a culture corrected to no
                // growth, a countersignature given): nothing is sent, and the alert is closed by the record.
                closeNoLongerUrgent(owed, now);
                return false;
            }
            sendFirstNotice(owed, alertOf(owed), now);
            return true;
        }
        if (closedIfAcknowledged(row)) {
            return false;
        }
        Optional<CriticalResultEscalationLadder.Step> due = stepDue(row, now);
        if (due.isEmpty()) {
            return false;
        }
        if (currency.noLongerUrgent(row)) {
            closeNoLongerUrgent(row, now);
            return false;
        }
        CriticalResultEscalationLadder.Step step = due.get();
        short current = row.getEscalationLevel();
        short next = (short) Math.min(current + 1, Short.MAX_VALUE);
        if (escalationRepository.claimRound(row.getId(), current, next, now) != 1) {
            return false;
        }
        Locale locale = NotificationLocales.STAFF;
        // QA D659: the sweep runs with no tenant context, where the tenant-scoped
        // findById sees no patient and every reminder said "for patient". The row
        // was raised at its hospital for this patient, and the reminder only goes
        // to that hospital's staff, so the unscoped read discloses nothing new.
        Optional<Patient> patient = patientRepository.findByIdUnscoped(row.getPatientId());
        Optional<String> knownName = patient.map(Patient::getFullName).filter(n -> !n.isBlank());
        String patientName = knownName
            .orElseGet(() -> messageSource.getMessage(PATIENT_FALLBACK, null, locale));
        Object patientArg = knownName.<Object>map(n -> n)
            .orElseGet(() -> NotificationLocalizer.key(PATIENT_FALLBACK));
        Object typeArg = typeLabelArg(row.getSourceType(), row.getAlertType(), row.getTypeLabel());
        Object messageArg = alertMessageArg(row.getAlertMessageKey(), row.getAlertMessageArgs(), row.getAlertMessage());
        String storedType = row.getTypeLabel() == null ? "" : row.getTypeLabel();
        String storedMessage = row.getAlertMessage() == null ? "" : row.getAlertMessage();
        boolean unresolved = UrgentEscalationSupport.unresolved(step);
        long minutes = UrgentEscalationSupport.minutesSince(row.getFirstNoticeFiledAt(), now);
        Object[] reminderArgs = {String.valueOf(next), typeArg, patientArg, messageArg};
        // The last step and every reminder after it say « non résolue » and for how long; the steps before say
        // which reminder this is. Staff-locale text only titles the in-basket item; notices are keyed (QA D665).
        String noticeKey = unresolved ? UNRESOLVED_KEY : REMINDER_KEY;
        Object[] noticeArgs = unresolved ? new Object[]{typeArg, patientArg, messageArg, minutes} : reminderArgs;
        String message = unresolved
            ? messageSource.getMessage(UNRESOLVED_KEY, new Object[]{storedType, patientName, storedMessage, minutes}, locale)
            : messageSource.getMessage(REMINDER_KEY, new Object[]{reminderArgs[0], storedType, patientName, storedMessage},
                locale);
        String documenter = usernameOf(row.getDocumentedByUserId());
        if (documenter != null) {
            // The documenter learns it is still unanswered; she cannot answer it herself.
            notifyQuietly(DOCUMENTER_REMINDER_KEY, reminderArgs, documenter, noticeType(row.getSourceType(), true),
                row.getId());
        }
        Set<String> circle = stepCircle(row, step, documenter);
        Delivery delivery = deliver(row, noticeKey, noticeArgs, message, patientName, circle,
            noticeType(row.getSourceType(), true), Set.of());
        if (delivery.nothingDelivered()) {
            // The exception gives the step back: the next sweep takes it again.
            throw new UrgentAlertNotDeliveredException();
        }
        if (ladder != null) {
            // The clinicians this step adds get the staff SMS (the patient's initials, nothing clinical).
            Set<String> added = new LinkedHashSet<>(ladder.addedAt(row.getHospitalId(), step, documenter));
            added.removeIf(u -> documenter != null && u.equalsIgnoreCase(documenter));
            ladder.smsAfterCommit(added, com.example.hms.utility.SmsPatientLabel.initials(patient.orElse(null)),
                row.getId());
            // From the last step on the alert is on the register of incidents; the administrators are flagged
            // once, when it opens, with no clinical text.
            if (unresolved) {
                ladder.openIncident(UrgentAlertIncident.SOURCE_URGENT_ALERT, row.getId(), row.getHospitalId(), now,
                    minutes, delivery.reached());
            }
        }
        if (delivery.reached() == 0) {
            log.error("Urgent alert {} at hospital {} reached the step {} with no clinician to tell",
                row.getId(), row.getHospitalId(), step);
        }
        return true;
    }

    /** The step of the ladder due now; without the ladder (a unit test of the circles), the default minutes. */
    private Optional<CriticalResultEscalationLadder.Step> stepDue(ObstetricAlertEscalation row, LocalDateTime now) {
        if (ladder != null) {
            return ladder.due(row.getEscalationLevel(), row.getFirstNoticeFiledAt(), row.getLastEscalationAt(),
                row.getHospitalId(), now);
        }
        return CriticalResultEscalationLadder.due(row.getEscalationLevel(), row.getFirstNoticeFiledAt(),
            row.getLastEscalationAt(), CriticalResultEscalationLadder.Minutes.DEFAULT, now);
    }

    /** Closed by the record itself, not by a person: acknowledgedBy stays empty, and its open tasks go. */
    private void closeNoLongerUrgent(ObstetricAlertEscalation row, LocalDateTime now) {
        closeIncident(row, UrgentAlertIncident.RESOLVED_NO_LONGER_URGENT, null, now);
        row.setAcknowledgedAt(now);
        row.setAcknowledgedBy(null);
        escalationRepository.save(row);
        // The withdrawn alert's open tasks no longer point at anything that stands.
        inBasketItemRepository.deleteOpenByReference(row.getId(), REFERENCE_TYPE);
    }

    private void closeIncident(ObstetricAlertEscalation row, String resolution, UUID byUserId, LocalDateTime at) {
        if (ladder != null) {
            ladder.closeIncident(UrgentAlertIncident.SOURCE_URGENT_ALERT, row.getId(), resolution, byUserId, at);
        }
    }

    /**
     * Scorecard K4: who a step of the ladder tells. The alert's own reminder circle (the obstetric circle widened
     * to the hospital's doctors, midwives and nurses; the patient's care team for an adult's vital set; the people
     * their module named for the other sources), and the senior clinicians of the step: the covering clinician,
     * then the médecin-chef. A transfusion alert also reaches the laboratory directors from the médecin-chef's
     * step on. Never an administrator.
     */
    Set<String> stepCircle(ObstetricAlertEscalation row, CriticalResultEscalationLadder.Step step, String documenter) {
        String source = row.getSourceType();
        Set<String> circle = stillHere(row.getNamedRecipients(), row.getHospitalId());
        if (among(ObstetricAlertEscalationService.GENERAL_SOURCES, source)) {
            // The people the module rang at the start, while they still hold a clinical role here.
            circle.addAll(stillHere(row.getAlreadyRang(), row.getHospitalId()));
        }
        if (!among(NAMED_CIRCLE_SOURCES, source)) {
            circle.addAll(reminderCircle(source, row.getHospitalId(), row.getPatientId(), documenter));
        }
        if (ladder != null) {
            circle.addAll(ladder.seniors(row.getHospitalId(), step, documenter));
            if (among(TRANSFUSION_SOURCES, source) && step != CriticalResultEscalationLadder.Step.COVER) {
                circle.addAll(ladder.biologists(row.getHospitalId()));
            }
        }
        return circle;
    }

    /** The sources whose circle is the people their module named, and the ladder's seniors. */
    private static final Set<String> NAMED_CIRCLE_SOURCES = Set.of(
        ObstetricAlertEscalationService.SOURCE_TRANSFUSION_REACTION,
        ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE,
        ObstetricAlertEscalationService.SOURCE_O_POSITIVE_COUNTERSIGN,
        ObstetricAlertEscalationService.SOURCE_INCOMPATIBLE_COUNTERSIGN);

    private static final Set<String> TRANSFUSION_SOURCES = Set.of(
        ObstetricAlertEscalationService.SOURCE_TRANSFUSION_REACTION,
        ObstetricAlertEscalationService.SOURCE_O_POSITIVE_COUNTERSIGN,
        ObstetricAlertEscalationService.SOURCE_INCOMPATIBLE_COUNTERSIGN);

    /** Whether {@code source} is one of {@code sources}; a row read with no source is none of them. */
    private static boolean among(Set<String> sources, String source) {
        return source != null && sources.contains(source);
    }

    /** An adult's vital set: the patient's care team stands for it, never the obstetric team. */
    private static final Set<String> ADULT_VITAL_SOURCES = Set.of(
        ObstetricAlertEscalationService.SOURCE_NEWS2, ObstetricAlertEscalationService.SOURCE_URGENT_HYPERTENSION);

    /**
     * QA D3862: an alert of a vital set's early warning repeats while the first one is still escalating: the same
     * patient, source and alert type, unacknowledged. A reading after an acknowledgement files a new one.
     * Scorecard K4: a record's own alert (a reaction, a culture, a countersignature) is filed once while it is open.
     */
    private boolean stillEscalating(ObstetricAlertEscalationService.UrgentAlert alert) {
        List<ObstetricAlertEscalation> open;
        if (among(ObstetricAlertEscalationService.ONE_OPEN_PER_RECORD, alert.sourceType())) {
            open = escalationRepository.findOpenForSource(alert.sourceType(), alert.sourceId());
        } else if (among(ObstetricAlertEscalationService.VITAL_SET_EARLY_WARNING_SOURCES, alert.sourceType())) {
            open = escalationRepository.findOpenForPatient(alert.hospitalId(), alert.patientId(), alert.sourceType(),
                alert.alertType() == null ? "" : alert.alertType());
        } else {
            return false;
        }
        // Review of #1370 (F1): a row whose item was acknowledged since the last sweep is answered; it is closed
        // now, with that acknowledgement, and holds nothing. Held on it, the new reading got no item, and the sweep
        // then closed the old row: nobody was told of the new one.
        boolean escalating = false;
        for (ObstetricAlertEscalation row : open) {
            if (!closedIfAcknowledged(row)) {
                escalating = true;
            }
        }
        return escalating;
    }

    /**
     * Closes an alert one of whose items somebody other than its documenter acknowledged, with that
     * acknowledgement. An alert only its record closes ({@code CLOSED_BY_RECORD_ONLY}) is never closed here.
     *
     * @return true when the row was closed now
     */
    private boolean closedIfAcknowledged(ObstetricAlertEscalation row) {
        if (among(ObstetricAlertEscalationService.CLOSED_BY_RECORD_ONLY, row.getSourceType())) {
            return false;
        }
        List<InBasketItem> acks = inBasketItemRepository.findAcknowledgedForReference(
            row.getId(), REFERENCE_TYPE, row.getDocumentedByUserId());
        if (acks.isEmpty()) {
            return false;
        }
        closeIncident(row, UrgentAlertIncident.RESOLVED_ACKNOWLEDGED, acks.get(0).getAcknowledgedBy(),
            acks.get(0).getAcknowledgedAt());
        row.setAcknowledgedAt(acks.get(0).getAcknowledgedAt());
        row.setAcknowledgedBy(acks.get(0).getAcknowledgedBy());
        escalationRepository.save(row);
        return true;
    }

    /**
     * Who takes the first notice of an alert.
     * <ul>
     *   <li>An adult NEWS2 (QA D3862): the people the vital set's page rang (the recorder, the stay's responsible
     *       doctor, the visit's clinician), the nurses of the patient's ward (the hospital's nurse in charge when
     *       the ward names none), and the covering doctor whenever no other doctor is among them. Never the
     *       obstetric team: an adult's NEWS2 is not a midwife's alert.</li>
     *   <li>A newborn finding, from the assessment or from a vital set: the obstetric circle, always with a
     *       physician (policy rule 2); a vital set's also keeps the people its page rang.</li>
     *   <li>Every other source: the obstetric circle.</li>
     * </ul>
     */
    Set<String> firstCircle(ObstetricAlertEscalationService.UrgentAlert alert) {
        UUID hospitalId = alert.hospitalId();
        String documenter = usernameOf(alert.documentedByUserId());
        if (among(NAMED_CIRCLE_SOURCES, alert.sourceType())) {
            return namedCircle(alert, documenter);
        }
        if (among(ADULT_VITAL_SOURCES, alert.sourceType())) {
            Set<String> circle = new LinkedHashSet<>(alert.alreadyNotified());
            circle.addAll(nursesInCharge(hospitalId, alert.patientId()));
            return withADoctor(circle, hospitalId, documenter);
        }
        Set<String> recipients = firstNoticeRecipients(hospitalId, documenter);
        if (ObstetricAlertEscalationService.SOURCE_NEWBORN_VITAL_SIGN.equals(alert.sourceType())) {
            Set<String> widened = new LinkedHashSet<>(alert.alreadyNotified());
            widened.addAll(recipients);
            recipients = widened;
        }
        if (ObstetricAlertEscalationService.SOURCE_NEWBORN.equals(alert.sourceType())
                || ObstetricAlertEscalationService.SOURCE_NEWBORN_VITAL_SIGN.equals(alert.sourceType())) {
            recipients = withAPhysician(recipients, hospitalId);
        }
        return recipients;
    }

    /**
     * Scorecard K4: the first circle of an alert whose module names its readers.
     * <ul>
     *   <li>A severe transfusion reaction: the clinician who asked for the blood, and the covering physician (on
     *       call, else on duty, else every doctor), always.</li>
     *   <li>An overdue countersignature: the laboratory directors; with none, the médecin-chef.</li>
     *   <li>A positive culture: the clinician who ordered it.</li>
     * </ul>
     * Whenever that leaves nobody but the documenter, the covering clinician takes it: no alert starts with nobody.
     */
    Set<String> namedCircle(ObstetricAlertEscalationService.UrgentAlert alert, String documenter) {
        UUID hospitalId = alert.hospitalId();
        Set<String> circle = stillHere(alert.namedRecipients(), hospitalId);
        boolean reaction = ObstetricAlertEscalationService.SOURCE_TRANSFUSION_REACTION.equals(alert.sourceType());
        if (!reaction && others(circle, documenter).isEmpty() && ladder != null
                && among(TRANSFUSION_SOURCES, alert.sourceType())) {
            circle.addAll(ladder.medicalLead(hospitalId));
        }
        if (reaction || others(circle, documenter).isEmpty()) {
            circle.addAll(coverOf(hospitalId, documenter));
        }
        return circle;
    }

    /**
     * The people a module named who still hold a clinical role at the alert's hospital, in the order they were
     * named. An alert carries the patient's name: somebody who has left the hospital since the order or the request
     * is not told; the covering clinician is.
     */
    Set<String> stillHere(java.util.Collection<String> named, UUID hospitalId) {
        Set<String> kept = new LinkedHashSet<>();
        if (named == null || named.isEmpty() || hospitalId == null) {
            return kept;
        }
        Set<String> here = new LinkedHashSet<>();
        for (String role : REMINDER_ROLES) {
            staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, role)
                .forEach(u -> here.add(u.toLowerCase(Locale.ROOT)));
        }
        for (String role : LADDER_ROLES) {
            staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, role)
                .forEach(u -> here.add(u.toLowerCase(Locale.ROOT)));
        }
        for (String name : named) {
            if (name != null && here.contains(name.toLowerCase(Locale.ROOT))) {
                kept.add(name);
            }
        }
        return kept;
    }

    private static List<String> others(Set<String> usernames, String documenter) {
        return usernames.stream().filter(Objects::nonNull)
            .filter(u -> documenter == null || !u.equalsIgnoreCase(documenter)).toList();
    }

    /** The covering clinician: on call, else on duty, else every doctor (every doctor without the roster reader). */
    private Set<String> coverOf(UUID hospitalId, String documenter) {
        if (ladder != null) {
            return ladder.cover(hospitalId, documenter);
        }
        return clinicalRecipients != null ? clinicalRecipients.cover(hospitalId, documenter) : doctors(hospitalId);
    }

    /**
     * Who each reminder goes to. An adult NEWS2 (QA D3862, review of #1339): the patient's care team (the responsible
     * doctor, the doctors on call, the ward's doctors and nurses on shift), never the midwives, and the hospital's
     * doctors and nurses only when that team holds nobody ({@code News2CareTeam}). Every other source: the obstetric
     * circle widened to the hospital's doctors, midwives and nurses.
     */
    Set<String> reminderCircle(String sourceType, UUID hospitalId, UUID patientId, String recorder) {
        if (!among(ADULT_VITAL_SOURCES, sourceType)) {
            return reminderRecipients(hospitalId);
        }
        if (careTeam != null) {
            return careTeam.reminderCircle(hospitalId, patientId, recorder);
        }
        Set<String> recipients = new LinkedHashSet<>(doctors(hospitalId));
        recipients.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, NURSE_ROLE));
        return recipients;
    }

    /**
     * The nurses of the patient's ward ({@code News2CareTeam}); when it names none, the critical-result ladder's nurse
     * in charge (on duty now, else every nurse); every nurse without either.
     */
    private Set<String> nursesInCharge(UUID hospitalId, UUID patientId) {
        Set<String> ward = careTeam == null ? Set.of() : careTeam.wardNurses(hospitalId, patientId);
        if (!ward.isEmpty()) {
            return ward;
        }
        if (clinicalRecipients != null) {
            return clinicalRecipients.nursesInCharge(hospitalId, true);
        }
        return new LinkedHashSet<>(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, NURSE_ROLE));
    }

    /** The active holders of a doctor role at the hospital, whichever of the three it assigned. */
    private Set<String> doctors(UUID hospitalId) {
        Set<String> doctors = new LinkedHashSet<>();
        for (String role : com.example.hms.service.lab.CriticalResultRecipients.DOCTOR_ROLES) {
            doctors.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, role));
        }
        return doctors;
    }

    /**
     * QA D3862: an adult's HIGH NEWS2 always reaches a doctor other than the recorder. When nobody else in the
     * circle holds a doctor role at the hospital (a triage set with no visit doctor, a stay with no attending),
     * the covering doctor is added: on call, else on duty, else every doctor.
     */
    private Set<String> withADoctor(Set<String> circle, UUID hospitalId, String documenter) {
        Set<String> doctorsLower = new LinkedHashSet<>();
        doctors(hospitalId).forEach(u -> doctorsLower.add(u.toLowerCase(Locale.ROOT)));
        boolean reached = circle.stream().anyMatch(u -> u != null && doctorsLower.contains(u.toLowerCase(Locale.ROOT))
            && (documenter == null || !u.equalsIgnoreCase(documenter)));
        if (reached) {
            return circle;
        }
        Set<String> widened = new LinkedHashSet<>(circle);
        widened.addAll(clinicalRecipients != null ? clinicalRecipients.cover(hospitalId, documenter) : doctors(hospitalId));
        return widened;
    }

    /**
     * The doctors and midwives on call at the hospital now; all its doctors and
     * midwives when none of them is. An on-call lab technician or pharmacist
     * never takes the first notice alone. The documenter is left out before
     * that fallback: when she holds the only on-call shift, the notice goes
     * to the rest of the team instead of to nobody.
     */
    Set<String> firstNoticeRecipients(UUID hospitalId, String documenter) {
        Set<String> obstetricTeam = new LinkedHashSet<>();
        for (String role : FIRST_NOTICE_ROLES) {
            obstetricTeam.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, role));
        }
        Set<String> teamLower = new LinkedHashSet<>();
        obstetricTeam.forEach(u -> teamLower.add(u.toLowerCase(Locale.ROOT)));
        Set<String> recipients = new LinkedHashSet<>();
        for (String username : onCallUsernames(hospitalId)) {
            if (teamLower.contains(username.toLowerCase(Locale.ROOT))
                    && (documenter == null || !username.equalsIgnoreCase(documenter))) {
                recipients.add(username);
            }
        }
        return recipients.isEmpty() ? obstetricTeam : recipients;
    }

    /**
     * Policy rule 2 (2026-11-20, provisional): a newborn's danger sign always reaches a physician.
     * When the first circle holds midwives only (an on-call midwife and no on-call doctor), the
     * hospital's doctors are added to it. Nothing is blocked and nobody is removed.
     */
    Set<String> withAPhysician(Set<String> recipients, UUID hospitalId) {
        List<String> doctors = staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, NEWBORN_PHYSICIAN_ROLE);
        Set<String> doctorsLower = new LinkedHashSet<>();
        doctors.forEach(u -> doctorsLower.add(u.toLowerCase(Locale.ROOT)));
        boolean reached = recipients.stream().anyMatch(u -> u != null && doctorsLower.contains(u.toLowerCase(Locale.ROOT)));
        if (reached || doctors.isEmpty()) {
            return recipients;
        }
        Set<String> widened = new LinkedHashSet<>(recipients);
        widened.addAll(doctors);
        return widened;
    }

    /** The first circle widened to the hospital's doctors, midwives and nurses. */
    Set<String> reminderRecipients(UUID hospitalId) {
        Set<String> recipients = new LinkedHashSet<>(firstNoticeRecipients(hospitalId, null));
        for (String role : REMINDER_ROLES) {
            recipients.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, role));
        }
        return recipients;
    }

    private List<String> onCallUsernames(UUID hospitalId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<String> usernames = new ArrayList<>();
        for (OnCallSchedule shift : onCallScheduleRepository.findByHospitalAndWindow(hospitalId, now, now)) {
            Staff staff = shift.getStaff();
            User user = staff == null ? null : staff.getUser();
            if (staff != null && staff.isActive() && user != null && user.getUsername() != null
                    && user.isActive() && !user.isDeleted()) {
                usernames.add(user.getUsername());
            }
        }
        return usernames;
    }

    /**
     * A notification and an in-basket item to acknowledge for every recipient
     * but the documenter. Each delivery is guarded: one bad recipient never
     * stops the others.
     *
     * @param notificationType the bell notice's type; {@code null} files the in-basket items with no bell notice
     *                         (the page that raised the alert already rang these people: QA D3304)
     * @param alreadyRang      the people the raising module already rang for this reading: an item, no bell (D3862)
     * @return who it was for (everybody but the documenter), what went out and what failed
     */
    private Delivery deliver(ObstetricAlertEscalation row, String messageKey, Object[] args, String message,
                        String patientName, Set<String> usernames, String notificationType, Set<String> alreadyRang) {
        String documenter = usernameOf(row.getDocumentedByUserId());
        List<String> others = usernames.stream()
            .filter(Objects::nonNull)
            .filter(u -> documenter == null || !u.equalsIgnoreCase(documenter))
            .toList();
        if (others.isEmpty()) {
            return Delivery.NONE;
        }
        Set<String> rangLower = new LinkedHashSet<>();
        alreadyRang.stream().filter(Objects::nonNull).forEach(u -> rangLower.add(u.toLowerCase(Locale.ROOT)));
        List<String> unrung = others.stream().filter(u -> !rangLower.contains(u.toLowerCase(Locale.ROOT))).toList();
        int delivered = ring(unrung, messageKey, args, notificationType, row.getId());
        int failed = notificationType == null ? 0 : unrung.size() - delivered;
        List<String> lower = others.stream().map(u -> u.toLowerCase(Locale.ROOT)).toList();
        String text = message == null ? "" : message;
        String title = text.length() > TITLE_MAX ? text.substring(0, TITLE_MAX) : text;
        // QA D1269 (round 26, D1053 sibling): the item was stored as staff-locale
        // (French) prose and read in French by an English or Spanish doctor. It
        // now carries the notice's key and arguments, and the alert sentence's
        // own key, so each reader's In-Basket renders it in their language; the
        // stored text stays the fallback for a row raised without a key.
        boolean bodyKeyed = row.getAlertMessageKey() != null && !row.getAlertMessageKey().isBlank();
        for (User user : userRepository.findByUsernameInIgnoreCase(lower)) {
            try {
                inBasketService.createItemInNewTransaction(CreateInBasketItemRequest.builder()
                    .recipientUserId(user.getId())
                    .hospitalId(row.getHospitalId())
                    .itemType(InBasketItemType.MESSAGE)
                    .priority(InBasketPriority.CRITICAL)
                    .title(title)
                    .titleKey(messageKey)
                    .titleArgs(args)
                    .body(row.getAlertMessage())
                    .bodyKey(bodyKeyed ? row.getAlertMessageKey() : null)
                    .bodyArgs(bodyKeyed && row.getAlertMessageArgs() != null ? row.getAlertMessageArgs().toArray() : null)
                    .referenceId(row.getId())
                    .referenceType(REFERENCE_TYPE)
                    .patientId(row.getPatientId())
                    .patientName(patientName)
                    .build());
                delivered++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("In-basket item for obstetric alert {} not filed: {}", row.getId(), ex.getClass().getSimpleName());
            }
        }
        return new Delivery(others.size(), delivered, failed);
    }

    /**
     * The bell's type: an adult's NEWS2 has its own (QA D3862), and so have the sources that are not obstetric
     * (scorecard K4); every other source is an obstetric alert.
     */
    static String noticeType(String sourceType, boolean reminder) {
        if (ObstetricAlertEscalationService.SOURCE_NEWS2.equals(sourceType)) {
            return reminder ? EARLY_WARNING_ESCALATION_TYPE : EARLY_WARNING_NOTIFICATION_TYPE;
        }
        if (among(ObstetricAlertEscalationService.GENERAL_SOURCES, sourceType)) {
            return reminder ? URGENT_ESCALATION_TYPE : URGENT_NOTIFICATION_TYPE;
        }
        return reminder ? ESCALATION_TYPE : NOTIFICATION_TYPE;
    }

    /** The bell notice of each recipient; none when {@code notificationType} is null (QA D3304). */
    private int ring(List<String> usernames, String messageKey, Object[] args, String notificationType, UUID escalationId) {
        if (notificationType == null) {
            return 0;
        }
        int rung = 0;
        for (String username : usernames) {
            if (notifyQuietly(messageKey, args, username, notificationType, escalationId)) {
                rung++;
            }
        }
        return rung;
    }

    private boolean notifyQuietly(String messageKey, Object[] args, String username, String type, UUID escalationId) {
        try {
            // Literal constants, so NotificationTypeLabelCoverageTest can see
            // every type this worker emits and require a label for each.
            if (ESCALATION_TYPE.equals(type)) {
                notificationService.createNotification(messageKey, args, username, ESCALATION_TYPE);
            } else if (EARLY_WARNING_ESCALATION_TYPE.equals(type)) {
                notificationService.createNotification(messageKey, args, username, EARLY_WARNING_ESCALATION_TYPE);
            } else if (EARLY_WARNING_NOTIFICATION_TYPE.equals(type)) {
                notificationService.createNotification(messageKey, args, username, EARLY_WARNING_NOTIFICATION_TYPE);
            } else if (URGENT_ESCALATION_TYPE.equals(type)) {
                notificationService.createNotification(messageKey, args, username, URGENT_ESCALATION_TYPE);
            } else if (URGENT_NOTIFICATION_TYPE.equals(type)) {
                notificationService.createNotification(messageKey, args, username, URGENT_NOTIFICATION_TYPE);
            } else {
                notificationService.createNotification(messageKey, args, username, NOTIFICATION_TYPE);
            }
            return true;
        } catch (RuntimeException ex) {
            log.warn("Obstetric alert {} notification not created: {}", escalationId, ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * QA D665: the alert sentence as a key the reader's locale renders, with its
     * own arguments; the stored staff-locale sentence for a row raised without one.
     */
    public static Object alertMessageArg(String messageKey, List<Object> messageArgs, String storedMessage) {
        if (messageKey == null || messageKey.isBlank()) {
            return storedMessage == null ? "" : storedMessage;
        }
        return NotificationLocalizer.key(messageKey, messageArgs == null ? null : messageArgs.toArray());
    }

    /** The source module's own notification key: the first notice reads as it always did. */
    public static String firstNoticeKey(String sourceType) {
        return typePrefix(sourceType) + "notification";
    }

    /** The alert type as a key the reader's locale resolves; the stored label when the type is unknown. */
    static Object typeLabelArg(String sourceType, String alertType, String storedLabel) {
        if (alertType == null || alertType.isBlank()) {
            return storedLabel == null ? "" : storedLabel;
        }
        return NotificationLocalizer.key(typePrefix(sourceType) + "type." + alertType);
    }

    /** Scorecard K4: the notice, type and sentence keys of the sources that are not obstetric. */
    public static final String URGENT_ALERT_PREFIX = "urgentAlert.";

    private static String typePrefix(String sourceType) {
        if (ObstetricAlertEscalationService.SOURCE_NEWBORN.equals(sourceType)
                || ObstetricAlertEscalationService.SOURCE_NEWBORN_VITAL_SIGN.equals(sourceType)) {
            return "newborn.alert.";
        }
        if (ObstetricAlertEscalationService.SOURCE_NEWS2.equals(sourceType)) {
            return "news2.alert.";
        }
        if (among(ObstetricAlertEscalationService.GENERAL_SOURCES, sourceType)) {
            return URGENT_ALERT_PREFIX;
        }
        if (ObstetricAlertEscalationService.SOURCE_POSTPARTUM.equals(sourceType)) {
            return "postpartum.alert.";
        }
        if (ObstetricAlertEscalationService.SOURCE_VITAL_SIGN.equals(sourceType)) {
            return "maternalWarning.alert.";
        }
        return "labor.alert.";
    }

    private String usernameOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(User::getUsername).orElse(null);
    }
}
