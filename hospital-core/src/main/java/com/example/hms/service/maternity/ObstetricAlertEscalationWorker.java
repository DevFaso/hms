package com.example.hms.service.maternity;

import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
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
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.NotificationLocalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${hms.maternity.alert-escalation.max-rounds:3}")
    private short maxRounds = 3;

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

    /** First notice of one URGENT alert: the row, the team's notifications and in-basket items. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID raise(ObstetricAlertEscalationService.UrgentAlert alert) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (stillEscalating(alert)) {
            // QA D3862: the alert this reading repeats is still reminding; a second item would only bury it.
            return null;
        }
        closeSupersededStillbirthEscalations(alert, now);
        ObstetricAlertEscalation row = escalationRepository.save(ObstetricAlertEscalation.builder()
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
            .build());
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
        int reached = deliver(row, firstNoticeKey(alert.sourceType()), args, alert.notificationText(),
            alert.patientName(), recipients, alreadyRang ? null : noticeType(alert.sourceType(), false),
            alert.alreadyNotified());
        if (reached == 0) {
            log.warn("Urgent obstetric alert {} at hospital {} has nobody on call and no other doctor or midwife to notify",
                row.getId(), alert.hospitalId());
        }
        return row.getId();
    }

    /**
     * QA D1801: a delivery's stillbirth alert is one alert. When a correction withdrew it and a later correction
     * raised it again before the sweep closed the first escalation, that first row would escalate beside the new
     * one. The new row replaces it: the old one is closed by the record (empty acknowledgedBy), like a withdrawn alert.
     */
    private void closeSupersededStillbirthEscalations(ObstetricAlertEscalationService.UrgentAlert alert,
                                                      LocalDateTime now) {
        if (!ObstetricAlertEscalationService.SOURCE_DELIVERY.equals(alert.sourceType())
            || !ObstetricAlertCurrency.STILLBIRTH_ALERT_MESSAGE_KEY.equals(alert.alertMessageKey())) {
            return;
        }
        for (ObstetricAlertEscalation open : escalationRepository.findOpenBySourceAndMessageKey(
                alert.hospitalId(), alert.sourceType(), alert.sourceId(), alert.alertMessageKey())) {
            open.setAcknowledgedAt(now);
            open.setAcknowledgedBy(null);
            escalationRepository.save(open);
            // Its open CRITICAL tasks go too: acknowledging one could not stop the replacement's reminders.
            inBasketItemRepository.deleteOpenByReference(open.getId(), REFERENCE_TYPE);
        }
    }

    /**
     * One reminder for one alert, after re-reading its current state: an
     * alert acknowledged (here or through its in-basket items) or already
     * escalated since the sweep listed it is left alone, and so is one the
     * clinical record no longer supports ({@link ObstetricAlertCurrency}):
     * it is closed with an empty acknowledgedBy.
     *
     * @return true when a reminder was sent
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean escalateOne(UUID escalationId) {
        Optional<ObstetricAlertEscalation> found = escalationRepository.findById(escalationId);
        if (found.isEmpty()) {
            return false;
        }
        ObstetricAlertEscalation row = found.get();
        if (row.getAcknowledgedAt() != null || row.getEscalationLevel() >= maxRounds) {
            return false;
        }
        List<InBasketItem> acks = inBasketItemRepository.findAcknowledgedForReference(
            row.getId(), REFERENCE_TYPE, row.getDocumentedByUserId());
        if (!acks.isEmpty()) {
            row.setAcknowledgedAt(acks.get(0).getAcknowledgedAt());
            row.setAcknowledgedBy(acks.get(0).getAcknowledgedBy());
            escalationRepository.save(row);
            return false;
        }
        if (currency.noLongerUrgent(row)) {
            // Closed by the record itself, not by a person: acknowledgedBy stays empty.
            row.setAcknowledgedAt(LocalDateTime.now(clock));
            row.setAcknowledgedBy(null);
            escalationRepository.save(row);
            // The withdrawn alert's open tasks no longer point at anything that stands.
            inBasketItemRepository.deleteOpenByReference(row.getId(), REFERENCE_TYPE);
            return false;
        }
        short current = row.getEscalationLevel();
        short next = (short) (current + 1);
        if (escalationRepository.claimRound(row.getId(), current, next, LocalDateTime.now(clock)) != 1) {
            return false;
        }
        Locale locale = NotificationLocales.STAFF;
        // QA D659: the sweep runs with no tenant context, where the tenant-scoped
        // findById sees no patient and every reminder said "for patient". The row
        // was raised at its hospital for this patient, and the reminder only goes
        // to that hospital's staff, so the unscoped read discloses nothing new.
        Optional<String> knownName = patientRepository.findByIdUnscoped(row.getPatientId())
            .map(Patient::getFullName)
            .filter(n -> !n.isBlank());
        String patientName = knownName
            .orElseGet(() -> messageSource.getMessage(PATIENT_FALLBACK, null, locale));
        Object patientArg = knownName.<Object>map(n -> n)
            .orElseGet(() -> NotificationLocalizer.key(PATIENT_FALLBACK));
        Object[] reminderArgs = {String.valueOf(next),
            typeLabelArg(row.getSourceType(), row.getAlertType(), row.getTypeLabel()), patientArg,
            alertMessageArg(row.getAlertMessageKey(), row.getAlertMessageArgs(), row.getAlertMessage())};
        // Staff-locale text, only for the in-basket title; notifications are keyed (QA D665).
        String message = messageSource.getMessage(REMINDER_KEY, new Object[]{reminderArgs[0],
            row.getTypeLabel() == null ? "" : row.getTypeLabel(), patientName,
            row.getAlertMessage() == null ? "" : row.getAlertMessage()}, locale);
        String documenter = usernameOf(row.getDocumentedByUserId());
        if (documenter != null) {
            // The documenter learns it is still unanswered; she cannot answer it herself.
            notifyQuietly(DOCUMENTER_REMINDER_KEY, reminderArgs, documenter, noticeType(row.getSourceType(), true),
                row.getId());
        }
        deliver(row, REMINDER_KEY, reminderArgs, message, patientName,
            reminderCircle(row.getSourceType(), row.getHospitalId(), row.getPatientId(), documenter),
            noticeType(row.getSourceType(), true), Set.of());
        if (next >= maxRounds) {
            log.warn("Urgent obstetric alert {} at hospital {} still unacknowledged after {} reminder(s); no more rounds",
                row.getId(), row.getHospitalId(), next);
        }
        return true;
    }

    /**
     * QA D3862: an alert of a vital set's early warning repeats while the first one is still reminding: the same
     * patient, source and alert type, unacknowledged, rounds left. A reading after the cap, or after an
     * acknowledgement, files a new one.
     */
    private boolean stillEscalating(ObstetricAlertEscalationService.UrgentAlert alert) {
        return ObstetricAlertEscalationService.VITAL_SET_EARLY_WARNING_SOURCES.contains(alert.sourceType())
            && escalationRepository.existsOpenForPatient(alert.hospitalId(), alert.patientId(), alert.sourceType(),
                alert.alertType() == null ? "" : alert.alertType(), maxRounds);
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
        if (ObstetricAlertEscalationService.SOURCE_NEWS2.equals(alert.sourceType())) {
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
     * Who each reminder goes to. An adult NEWS2 (QA D3862, review of #1339): the patient's care team (the responsible
     * doctor, the doctors on call, the ward's doctors and nurses on shift), never the midwives, and the hospital's
     * doctors and nurses only when that team holds nobody ({@code News2CareTeam}). Every other source: the obstetric
     * circle widened to the hospital's doctors, midwives and nurses.
     */
    Set<String> reminderCircle(String sourceType, UUID hospitalId, UUID patientId, String recorder) {
        if (!ObstetricAlertEscalationService.SOURCE_NEWS2.equals(sourceType)) {
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
     * @return how many people other than the documenter were notified
     */
    private int deliver(ObstetricAlertEscalation row, String messageKey, Object[] args, String message,
                        String patientName, Set<String> usernames, String notificationType, Set<String> alreadyRang) {
        String documenter = usernameOf(row.getDocumentedByUserId());
        List<String> others = usernames.stream()
            .filter(Objects::nonNull)
            .filter(u -> documenter == null || !u.equalsIgnoreCase(documenter))
            .toList();
        if (others.isEmpty()) {
            return 0;
        }
        Set<String> rangLower = new LinkedHashSet<>();
        alreadyRang.stream().filter(Objects::nonNull).forEach(u -> rangLower.add(u.toLowerCase(Locale.ROOT)));
        List<String> unrung = others.stream().filter(u -> !rangLower.contains(u.toLowerCase(Locale.ROOT))).toList();
        ring(unrung, messageKey, args, notificationType, row.getId());
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
            } catch (RuntimeException ex) {
                log.warn("In-basket item for obstetric alert {} not filed: {}", row.getId(), ex.getClass().getSimpleName());
            }
        }
        return others.size();
    }

    /** The bell's type: an adult's NEWS2 has its own (QA D3862); every other source is an obstetric alert. */
    static String noticeType(String sourceType, boolean reminder) {
        if (ObstetricAlertEscalationService.SOURCE_NEWS2.equals(sourceType)) {
            return reminder ? EARLY_WARNING_ESCALATION_TYPE : EARLY_WARNING_NOTIFICATION_TYPE;
        }
        return reminder ? ESCALATION_TYPE : NOTIFICATION_TYPE;
    }

    /** The bell notice of each recipient; none when {@code notificationType} is null (QA D3304). */
    private void ring(List<String> usernames, String messageKey, Object[] args, String notificationType, UUID escalationId) {
        if (notificationType == null) {
            return;
        }
        for (String username : usernames) {
            notifyQuietly(messageKey, args, username, notificationType, escalationId);
        }
    }

    private void notifyQuietly(String messageKey, Object[] args, String username, String type, UUID escalationId) {
        try {
            // Literal constants, so NotificationTypeLabelCoverageTest can see
            // every type this worker emits and require a label for each.
            if (ESCALATION_TYPE.equals(type)) {
                notificationService.createNotification(messageKey, args, username, ESCALATION_TYPE);
            } else if (EARLY_WARNING_ESCALATION_TYPE.equals(type)) {
                notificationService.createNotification(messageKey, args, username, EARLY_WARNING_ESCALATION_TYPE);
            } else if (EARLY_WARNING_NOTIFICATION_TYPE.equals(type)) {
                notificationService.createNotification(messageKey, args, username, EARLY_WARNING_NOTIFICATION_TYPE);
            } else {
                notificationService.createNotification(messageKey, args, username, NOTIFICATION_TYPE);
            }
        } catch (RuntimeException ex) {
            log.warn("Obstetric alert {} notification not created: {}", escalationId, ex.getClass().getSimpleName());
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

    private static String typePrefix(String sourceType) {
        if (ObstetricAlertEscalationService.SOURCE_NEWBORN.equals(sourceType)
                || ObstetricAlertEscalationService.SOURCE_NEWBORN_VITAL_SIGN.equals(sourceType)) {
            return "newborn.alert.";
        }
        if (ObstetricAlertEscalationService.SOURCE_NEWS2.equals(sourceType)) {
            return "news2.alert.";
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
