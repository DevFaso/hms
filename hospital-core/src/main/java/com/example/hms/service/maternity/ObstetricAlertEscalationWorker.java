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

    private static final List<String> FIRST_NOTICE_ROLES = List.of("ROLE_DOCTOR", "ROLE_MIDWIFE");
    private static final List<String> REMINDER_ROLES = List.of("ROLE_DOCTOR", "ROLE_MIDWIFE", "ROLE_NURSE");
    private static final int TITLE_MAX = 500;

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

    /** First notice of one URGENT alert: the row, the team's notifications and in-basket items. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID raise(ObstetricAlertEscalationService.UrgentAlert alert) {
        LocalDateTime now = LocalDateTime.now(clock);
        ObstetricAlertEscalation row = escalationRepository.save(ObstetricAlertEscalation.builder()
            .hospitalId(alert.hospitalId())
            .patientId(alert.patientId())
            .sourceType(alert.sourceType())
            .sourceId(alert.sourceId())
            .alertType(alert.alertType())
            .typeLabel(alert.typeLabel())
            .alertMessage(alert.alertMessage())
            .documentedByUserId(alert.documentedByUserId())
            .lastEscalationAt(now)
            .build());
        Set<String> recipients = firstNoticeRecipients(alert.hospitalId(),
            usernameOf(alert.documentedByUserId()));
        int reached = deliver(row, alert.notificationText(), alert.patientName(), recipients, NOTIFICATION_TYPE);
        if (reached == 0) {
            log.warn("Urgent obstetric alert {} at hospital {} has nobody on call and no other doctor or midwife to notify",
                row.getId(), alert.hospitalId());
        }
        return row.getId();
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
        String patientName = patientRepository.findByIdUnscoped(row.getPatientId())
            .map(Patient::getFullName)
            .filter(n -> !n.isBlank())
            .orElseGet(() -> messageSource.getMessage("patient.fallback.generic", null, locale));
        String message = messageSource.getMessage("obstetric.alert.escalation",
            new Object[]{String.valueOf(next), row.getTypeLabel() == null ? "" : row.getTypeLabel(), patientName,
                row.getAlertMessage() == null ? "" : row.getAlertMessage()}, locale);
        String documenter = usernameOf(row.getDocumentedByUserId());
        if (documenter != null) {
            // The documenter learns it is still unanswered; she cannot answer it herself.
            notifyQuietly(message, documenter, ESCALATION_TYPE, row.getId());
        }
        deliver(row, message, patientName, reminderRecipients(row.getHospitalId()), ESCALATION_TYPE);
        if (next >= maxRounds) {
            log.warn("Urgent obstetric alert {} at hospital {} still unacknowledged after {} reminder(s); no more rounds",
                row.getId(), row.getHospitalId(), next);
        }
        return true;
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
     * @return how many people other than the documenter were notified
     */
    private int deliver(ObstetricAlertEscalation row, String message, String patientName,
                        Set<String> usernames, String notificationType) {
        String documenter = usernameOf(row.getDocumentedByUserId());
        List<String> others = usernames.stream()
            .filter(Objects::nonNull)
            .filter(u -> documenter == null || !u.equalsIgnoreCase(documenter))
            .toList();
        if (others.isEmpty()) {
            return 0;
        }
        for (String username : others) {
            notifyQuietly(message, username, notificationType, row.getId());
        }
        List<String> lower = others.stream().map(u -> u.toLowerCase(Locale.ROOT)).toList();
        String title = message.length() > TITLE_MAX ? message.substring(0, TITLE_MAX) : message;
        for (User user : userRepository.findByUsernameInIgnoreCase(lower)) {
            try {
                inBasketService.createItemInNewTransaction(CreateInBasketItemRequest.builder()
                    .recipientUserId(user.getId())
                    .hospitalId(row.getHospitalId())
                    .itemType(InBasketItemType.MESSAGE)
                    .priority(InBasketPriority.CRITICAL)
                    .title(title)
                    .body(row.getAlertMessage())
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

    private void notifyQuietly(String message, String username, String type, UUID escalationId) {
        try {
            // Literal constants, so NotificationTypeLabelCoverageTest can see
            // every type this worker emits and require a label for each.
            if (ESCALATION_TYPE.equals(type)) {
                notificationService.createNotification(message, username, ESCALATION_TYPE);
            } else {
                notificationService.createNotification(message, username, NOTIFICATION_TYPE);
            }
        } catch (RuntimeException ex) {
            log.warn("Obstetric alert {} notification not created: {}", escalationId, ex.getClass().getSimpleName());
        }
    }

    private String usernameOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(User::getUsername).orElse(null);
    }
}
