package com.example.hms.service.credentialing;

import com.example.hms.enums.LicenseAlertStage;
import com.example.hms.model.Staff;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the licence-expiry rows nobody reads into notifications somebody
 * receives (Tier 2 item 40).
 *
 * <p>The data and the grading already existed; the delivery did not. A
 * hospital administrator learned that a doctor's licence had expired only by
 * opening a dashboard page and noticing, which means in practice they learned
 * it when somebody else did.
 *
 * <p><b>The stage guard is the point.</b> A staff member is notified when
 * their grade <em>advances</em> — nothing to WARNING, WARNING to CRITICAL,
 * CRITICAL to EXPIRED — and not again in between. A sweep that re-sent the
 * same warning every morning would be worse than no sweep: within a week the
 * administrator filters the category, and the one that mattered goes with it.
 * Recording a renewal clears the stage, so a licence that drifts toward
 * expiry again alerts again from scratch.
 *
 * <p>Notifies the practitioner as well as the administrators. The
 * practitioner is the only person who can actually obtain the renewal, and
 * telling only the person who enforces it is how this becomes an argument
 * rather than a renewal.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LicenseExpirySweepService {

    /** Role whose holders are told about their colleagues' licences. */
    static final String ADMIN_ROLE = "HOSPITAL_ADMIN";

    static final String NOTIFICATION_TYPE = "LICENSE_EXPIRY";

    private final StaffRepository staffRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    /**
     * Grade every active practitioner's licence and notify on advances.
     *
     * @return how many staff members were notified this run
     */
    @Transactional
    public int sweep() {
        LocalDate today = LocalDate.now(clock);
        LocalDate horizon = today.plusDays(LicenseAlertStage.WARNING_DAYS);

        List<Staff> candidates = staffRepository.findAllWithLicenseExpiringBefore(horizon);
        int notified = 0;

        for (Staff staff : candidates) {
            LicenseAlertStage stage = LicenseAlertStage.grade(staff.getLicenseExpiryDate(), today);
            if (stage == null || !stage.isMoreSevereThan(staff.getLicenseAlertStage())) {
                continue;
            }

            // Stamped whether or not a recipient could be resolved. A staff
            // member with no reachable administrator would otherwise be
            // re-attempted every night forever, which is the spam this guard
            // exists to prevent, and the warning below is the better signal.
            staff.setLicenseAlertStage(stage);
            staffRepository.save(staff);

            Set<String> recipients = resolveRecipients(staff);
            if (recipients.isEmpty()) {
                log.warn("Licence {} for staff {} but nobody to notify — no active "
                    + "{} and no reachable practitioner account",
                    stage, staff.getId(), ADMIN_ROLE);
                continue;
            }

            String message = buildMessage(staff, stage, today);
            for (String recipient : recipients) {
                notificationService.createNotification(message, recipient, NOTIFICATION_TYPE);
            }
            notified++;
        }

        log.info("Licence expiry sweep finished — {} of {} candidate(s) notified",
            notified, candidates.size());
        return notified;
    }

    private Set<String> resolveRecipients(Staff staff) {
        // LinkedHashSet: a practitioner who is also the hospital administrator
        // must not receive the same notification twice, and the order keeps
        // the logs readable.
        Set<String> recipients = new LinkedHashSet<>();

        if (staff.getUser() != null && staff.getUser().getUsername() != null) {
            recipients.add(staff.getUser().getUsername());
        }

        UUID hospitalId = staff.getHospital() != null ? staff.getHospital().getId() : null;
        if (hospitalId != null) {
            recipients.addAll(
                staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, ADMIN_ROLE));
        }
        return recipients;
    }

    private String buildMessage(Staff staff, LicenseAlertStage stage, LocalDate today) {
        String name = staff.getUser() != null && staff.getUser().getUsername() != null
            ? staff.getUser().getUsername()
            : staff.getId().toString();
        LocalDate expiry = staff.getLicenseExpiryDate();

        if (stage == LicenseAlertStage.EXPIRED) {
            return "Practising licence for " + name + " expired on " + expiry + ".";
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, expiry);
        return "Practising licence for " + name + " expires on " + expiry
            + " (" + days + " day(s)).";
    }
}
