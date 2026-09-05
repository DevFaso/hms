package com.example.hms.service.pro;

import com.example.hms.enums.PanelAssignmentStatus;
import com.example.hms.enums.PanelRole;
import com.example.hms.model.PanelAssignment;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.pro.ProResponse;
import com.example.hms.repository.PanelAssignmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pro.ProResponseRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The "somebody must look at this" loop for a screening (Tier 2 item 47).
 * Template: {@link com.example.hms.service.CriticalValueNotificationService}
 * — the only escalation chain in the product that actually repeats.
 *
 * <p>Two grades of alert:
 * <ul>
 *   <li><b>Screen-positive</b> (total at/above the instrument's threshold):
 *       one notification to the care team. It is a referral prompt, not an
 *       emergency, and the postpartum plan's outstanding-referral flag
 *       carries it from there.</li>
 *   <li><b>Critical-item-positive</b> (EPDS item 10, thoughts of
 *       self-harm): notified on write, then re-escalated by the sweep until
 *       someone acknowledges it. Round 1 goes to the people who own the
 *       patient; from round 2 the hospital's admins are added; no round
 *       cap, for the same reason the lab chain has none — going quiet on a
 *       self-harm disclosure is the failure this exists to prevent.</li>
 * </ul>
 *
 * <p>Who owns the patient: the recorder (a clinician who administered it
 * is the natural first responder), plus the item-37 panel owners
 * (PRIMARY_PROVIDER and CHW). A self-report from the patient portal has
 * no recorder, and a patient may have no panel — then the alert falls
 * through to every active {@code hms.pro.critical-escalation.fallback-role}
 * at the hospital, so a mother who answered at 2am on her phone is never
 * the one case with nobody on the list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProScreeningEscalationService {

    public static final String SCREEN_POSITIVE_TYPE = "PRO_SCREEN_POSITIVE";
    public static final String CRITICAL_TYPE = "PRO_CRITICAL_ITEM";
    public static final String ESCALATION_TYPE = "PRO_CRITICAL_ITEM_ESCALATION";

    private static final int TIER_TWO_ROUND = 2;
    private static final String TIER_TWO_ROLE = "ROLE_HOSPITAL_ADMIN";

    private final NotificationService notificationService;
    private final SmsService smsService;
    private final ProResponseRepository responseRepository;
    private final PanelAssignmentRepository panelAssignmentRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;

    @Value("${hms.pro.critical-escalation.escalate-after-minutes:30}")
    private long escalateAfterMinutes;

    /** Role notified when a critical response has no recorder and no panel owner. */
    @Value("${hms.pro.critical-escalation.fallback-role:ROLE_MIDWIFE}")
    private String fallbackRole;

    /**
     * Write-time notification. Never propagates: the response is the record
     * and a notification failure must not roll it back — the sweep picks a
     * critical row up anyway, because {@code notifiedAt} stays null.
     */
    public void notifyOnRecord(ProResponse response) {
        try {
            if (response.isCriticalItemPositive()) {
                Set<String> recipients = recipients(response, 1);
                String message = buildMessage(response, true, false);
                for (String recipient : recipients) {
                    notificationService.createNotification(message, recipient, CRITICAL_TYPE);
                }
                sendSmsBestEffort(response, message);
                if (recipients.isEmpty()) {
                    log.warn("Critical PRO response {} has nobody to notify at hospital {}",
                        response.getId(), response.getHospital().getId());
                }
            } else if (response.isScreenPositive()) {
                String message = buildMessage(response, false, false);
                for (String recipient : recipients(response, 1)) {
                    notificationService.createNotification(message, recipient, SCREEN_POSITIVE_TYPE);
                }
            } else {
                return;
            }
            response.setNotifiedAt(LocalDateTime.now());
            responseRepository.save(response);
        } catch (RuntimeException ex) {
            log.warn("PRO screening notification failed for response {}: {}",
                response.getId(), ex.getMessage(), ex);
        }
    }

    /**
     * Re-escalate critical responses still unacknowledged past the delay.
     * Per-row failures are logged and skipped so one bad row never stalls
     * the sweep.
     *
     * @return rows escalated on this pass
     */
    @Transactional
    public int escalateOverdue() {
        LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMinutes(escalateAfterMinutes));
        List<ProResponse> overdue = responseRepository.findCriticalAwaitingEscalation(cutoff);
        int escalated = 0;
        for (ProResponse response : overdue) {
            try {
                escalateOne(response);
                escalated++;
            } catch (RuntimeException ex) {
                log.warn("PRO screening escalation failed for response {}: {}",
                    response.getId(), ex.getMessage(), ex);
            }
        }
        return escalated;
    }

    private void escalateOne(ProResponse response) {
        int round = response.getEscalationLevel() + 1;
        String message = buildMessage(response, true, true);
        for (String recipient : recipients(response, round)) {
            notificationService.createNotification(message, recipient, ESCALATION_TYPE);
        }
        // SMS once per round, not once per recipient (same reasoning as the lab chain).
        sendSmsBestEffort(response, message);

        // Stamp even with no resolvable recipient so the interval advances
        // and the sweep does not reconsider the row every pass.
        response.setEscalationLevel((short) Math.min(round, Short.MAX_VALUE));
        response.setLastEscalationAt(LocalDateTime.now());
        responseRepository.save(response);

        if (round >= TIER_TWO_ROUND) {
            log.warn("Critical PRO response {} still unacknowledged after {} escalation round(s)",
                response.getId(), round);
        }
    }

    /**
     * Who hears about round {@code round}: recorder + panel owners; the
     * fallback role when that set is empty; hospital admins added from
     * round 2. Earlier recipients stay on the list — the point is to widen
     * the net, not hand the problem off.
     */
    Set<String> recipients(ProResponse response, int round) {
        Set<String> recipients = new LinkedHashSet<>();
        UUID hospitalId = response.getHospital() != null ? response.getHospital().getId() : null;
        UUID patientId = response.getPatient() != null ? response.getPatient().getId() : null;

        String recorder = usernameOf(response.getRecordedByUserId());
        if (recorder != null) {
            recipients.add(recorder);
        }
        if (hospitalId != null && patientId != null) {
            for (PanelRole role : new PanelRole[] {PanelRole.PRIMARY_PROVIDER, PanelRole.CHW}) {
                panelAssignmentRepository
                    .findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                        patientId, hospitalId, role, PanelAssignmentStatus.ACTIVE)
                    .map(this::usernameOf)
                    .ifPresent(recipients::add);
            }
        }
        if (recipients.isEmpty() && hospitalId != null) {
            recipients.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, fallbackRole));
        }
        if (round >= TIER_TWO_ROUND && hospitalId != null) {
            recipients.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, TIER_TWO_ROLE));
        }
        return recipients;
    }

    private String usernameOf(PanelAssignment assignment) {
        Staff staff = assignment.getProviderStaff();
        User user = staff != null ? staff.getUser() : null;
        return user != null ? user.getUsername() : null;
    }

    private String usernameOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(User::getUsername).orElse(null);
    }

    /** One text per round, to the recorder — the person with the chart open. */
    private void sendSmsBestEffort(ProResponse response, String message) {
        if (!smsService.deliversRealSms()) {
            return; // mock transport would only log — never route clinical alerts there
        }
        UUID userId = response.getRecordedByUserId();
        if (userId == null) {
            return;
        }
        String phone = userRepository.findById(userId).map(User::getPhoneNumber).orElse(null);
        if (phone == null || phone.isBlank()) {
            return;
        }
        try {
            smsService.send(phone, message);
        } catch (RuntimeException ex) {
            log.warn("PRO screening SMS failed for response {}: {}", response.getId(), ex.getMessage());
        }
    }

    /**
     * No answers, no score, no item text in the message: a notification
     * row and an SMS are both plaintext. The recipient opens the chart.
     */
    private String buildMessage(ProResponse response, boolean critical, boolean escalation) {
        String instrument = response.getInstrument() != null ? response.getInstrument().getCode() : "screening";
        String patientName = response.getPatient() != null && response.getPatient().getFullName() != null
            ? response.getPatient().getFullName() : "patient";
        String prefix;
        if (escalation) {
            prefix = "ESCALATION - unacknowledged " + instrument + " safety response for ";
        } else if (critical) {
            prefix = instrument + " safety item answered positively for ";
        } else {
            prefix = instrument + " screen positive for ";
        }
        String action = critical ? ". Review and acknowledge in HMS." : ". Follow-up is due.";
        return prefix + patientName + action;
    }
}
