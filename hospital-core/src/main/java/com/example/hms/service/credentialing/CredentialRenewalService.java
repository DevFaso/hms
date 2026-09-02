package com.example.hms.service.credentialing;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffCredentialRenewal;
import com.example.hms.model.User;
import com.example.hms.repository.StaffCredentialRenewalRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Recording a practitioner's credential renewal (Tier 2 item 40).
 *
 * <p><b>What was already there and did nothing.</b> {@code license_number}
 * and {@code license_expiry_date} have been on {@code hospital.staff} since
 * V1. {@code StaffRepository} has a query named "MVP 19: License expiry
 * alerts". {@code HospitalAdminDashboardServiceImpl} grades each result
 * EXPIRED / CRITICAL / WARNING. Nothing renewed, verified, or told anybody.
 * The system knew a clinician's licence expired next week and mentioned it
 * only to whoever happened to open a dashboard page.
 *
 * <p><b>This records, it does not block, and that is now a settled
 * decision.</b> An expired licence still prescribes, still signs, still logs
 * in. The product owner decided on 2026-08-26 that it stays that way: an
 * administrator who forgets to enter a renewal would otherwise take a working
 * doctor offline mid-shift in a hospital that may have one, and the cost of
 * that failure is worse than the cost of a lapsed date going unenforced by
 * software. <b>Do not add a block here without a new decision</b> — this is
 * not an unfinished edge, it is the chosen behaviour.
 *
 * <p><b>Not self-service.</b> A practitioner cannot record their own
 * renewal. Credentialing is somebody attesting they saw the document; a
 * practitioner attesting to their own is the same non-check the co-sign and
 * pharmacist-verification ceremonies already refuse. Server identity and
 * server clock throughout, for the same reason.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CredentialRenewalService {

    private final StaffRepository staffRepository;
    private final StaffCredentialRenewalRepository renewalRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final Clock clock;

    /**
     * Record a renewal and move the staff member's live credential forward.
     *
     * @param staffId          whose credential
     * @param expiryDate       the new expiry, or null for a qualification
     *                         that does not expire — see the entity. A null
     *                         also CLEARS any expiry already on the staff
     *                         row, which is the point: it says this person is
     *                         credentialed on something that has no end date,
     *                         and it takes them out of the expiry sweep.
     * @param licenseNumber    null keeps the number already on file, because
     *                         most renewals reissue the same number
     * @param issuingAuthority null keeps what is on file, same reasoning
     * @param note             optional free text
     */
    @Transactional
    public StaffCredentialRenewal recordRenewal(UUID staffId,
                                                LocalDate expiryDate,
                                                String licenseNumber,
                                                String issuingAuthority,
                                                String note) {
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("staff.notfound"));

        // 404-not-403: another hospital's staff member is indistinguishable
        // from one that does not exist.
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null
                && staff.getHospital() != null
                && !hospitalId.equals(staff.getHospital().getId())) {
            throw new ResourceNotFoundException("staff.notfound");
        }

        UUID currentUserId = roleValidator.getCurrentUserId();
        if (currentUserId == null) {
            throw new AccessDeniedException("Unable to determine who is recording this renewal.");
        }
        User recorder = userRepository.findById(currentUserId)
            .orElseThrow(() -> new AccessDeniedException(
                "Unable to determine who is recording this renewal."));

        if (staff.getUser() != null && currentUserId.equals(staff.getUser().getId())) {
            throw new BusinessException(
                "A practitioner cannot record their own credential renewal.");
        }

        // Captured BEFORE the staff row is mutated — this pair is the whole
        // reason the history table exists, and reading it back afterwards
        // would record the new values twice.
        String previousNumber = staff.getLicenseNumber();
        LocalDate previousExpiry = staff.getLicenseExpiryDate();

        // A renewal that does not extend the licence is almost always a typo,
        // but not always: an administrator correcting a date entered wrong
        // needs to be able to. So it is allowed and the history makes the
        // shortening visible rather than silent.
        if (expiryDate != null && previousExpiry != null && !expiryDate.isAfter(previousExpiry)) {
            log.warn("Credential renewal for staff {} does not extend the licence "
                + "({} -> {}). Allowed as a correction; recorded in the history.",
                staffId, previousExpiry, expiryDate);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        staff.setLicenseExpiryDate(expiryDate);
        if (trimToNull(licenseNumber) != null) {
            staff.setLicenseNumber(licenseNumber.trim());
        }
        if (trimToNull(issuingAuthority) != null) {
            staff.setLicenseIssuingAuthority(issuingAuthority.trim());
        }
        staff.setLicenseVerifiedAt(now);
        staff.setLicenseVerifiedBy(recorder);
        // Cleared so a licence that later drifts toward expiry alerts again
        // from scratch rather than staying silent behind a stale stage.
        staff.setLicenseAlertStage(null);
        staffRepository.save(staff);

        StaffCredentialRenewal renewal = StaffCredentialRenewal.builder()
            .staff(staff)
            .hospital(staff.getHospital())
            .previousLicenseNumber(previousNumber)
            .previousExpiryDate(previousExpiry)
            .licenseNumber(staff.getLicenseNumber())
            .expiryDate(expiryDate)
            .issuingAuthority(staff.getLicenseIssuingAuthority())
            .note(trimToNull(note))
            .recordedBy(recorder)
            .recordedAt(now)
            .build();

        log.info("Credential recorded for staff {} by user {} — expiry {} -> {}",
            staffId, currentUserId, previousExpiry,
            expiryDate != null ? expiryDate : "none (does not expire)");
        return renewalRepository.save(renewal);
    }

    /** One staff member's renewal history, newest first. */
    @Transactional(readOnly = true)
    public List<StaffCredentialRenewal> history(UUID staffId) {
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("staff.notfound"));

        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null
                && staff.getHospital() != null
                && !hospitalId.equals(staff.getHospital().getId())) {
            throw new ResourceNotFoundException("staff.notfound");
        }
        return renewalRepository.findByStaffIdOrderByRecordedAtDesc(staffId);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
