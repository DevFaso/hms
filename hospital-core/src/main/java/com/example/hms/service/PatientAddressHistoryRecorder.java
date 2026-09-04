package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAddressHistory;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.PatientAddressHistoryRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * The one place a patient's address change turns into a history row
 * (Tier 2 item 38). Shared by BOTH write surfaces — the staff paths in
 * {@code PatientServiceImpl} and the patient's own
 * {@code PatientPortalServiceImpl.updateMyProfile} — so a move records the
 * same way regardless of who typed it (review lesson on PR #550: the hook
 * originally lived in one service and the self-service path silently
 * bypassed it).
 *
 * <p>Contract: a history row means "the patient moved". The first fill-in
 * of a blank address records nothing; re-stating the same address records
 * nothing; the composed {@code address} string is derived formatting and
 * only participates in the comparison for LEGACY rows whose component
 * fields are all blank (address-only patients would otherwise never record
 * a move at all). Each write emits a best-effort PATIENT_UPDATE audit event
 * carrying the authenticated actor.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatientAddressHistoryRecorder {

    private final PatientAddressHistoryRepository addressHistoryRepository;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditService;

    /** The address as it stood before a mutation. */
    public record AddressSnapshot(String address, String line1, String line2, String city,
                                  String state, String zip, String country) {

        public static AddressSnapshot of(Patient p) {
            return new AddressSnapshot(p.getAddress(), p.getAddressLine1(), p.getAddressLine2(),
                p.getCity(), p.getState(), p.getZipCode(), p.getCountry());
        }

        boolean isBlank() {
            return blank(address) && componentsBlank();
        }

        boolean componentsBlank() {
            return blank(line1) && blank(line2) && blank(city)
                && blank(state) && blank(zip) && blank(country);
        }

        /**
         * Component fields decide identity; the composed {@code address}
         * only breaks the tie for legacy rows with NO components at all —
         * without that, an address-only patient could move forever and
         * never record a thing.
         */
        boolean sameLocationAs(AddressSnapshot other) {
            boolean componentsEqual = Objects.equals(line1, other.line1)
                && Objects.equals(line2, other.line2)
                && Objects.equals(city, other.city)
                && Objects.equals(state, other.state)
                && Objects.equals(zip, other.zip)
                && Objects.equals(country, other.country);
            if (!componentsEqual) {
                return false;
            }
            if (!componentsBlank()) {
                return true;
            }
            return Objects.equals(normalized(address), normalized(other.address));
        }

        private static String normalized(String value) {
            return value == null ? null : value.strip();
        }

        private static boolean blank(String v) {
            return v == null || v.isBlank();
        }
    }

    public AddressSnapshot snapshot(Patient patient) {
        return AddressSnapshot.of(patient);
    }

    /**
     * Writes one history row holding the OLD address when the patient's
     * address actually changed; no-op otherwise.
     */
    public void recordIfMoved(Patient patient, AddressSnapshot before) {
        if (before.isBlank() || before.sameLocationAs(AddressSnapshot.of(patient))) {
            return;
        }
        PatientAddressHistory saved = addressHistoryRepository.save(PatientAddressHistory.builder()
            .patient(patient)
            .address(composedLine(before))
            .city(before.city())
            .country(before.country())
            .build());
        log.info("Address history recorded for patient {} — previous address superseded.",
            patient.getId());
        emitAudit(patient, saved);
    }

    /**
     * The stored composed line can lag the parts (the full-form path sets
     * it from the DTO, which may omit it) — compose from the parts when
     * blank so the row a clinician reads is never empty.
     */
    private static String composedLine(AddressSnapshot before) {
        if (before.address() != null && !before.address().isBlank()) {
            return before.address();
        }
        String joined = Stream.of(before.line1(), before.line2(), before.city(),
                before.state(), before.zip(), before.country())
            .filter(part -> part != null && !part.isBlank())
            .map(String::strip)
            .reduce((a, b) -> a + ", " + b)
            .orElse(null);
        return joined;
    }

    /** Best-effort: an audit failure must never undo the recorded move. */
    private void emitAudit(Patient patient, PatientAddressHistory saved) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.PATIENT_UPDATE)
                .status(AuditStatus.SUCCESS)
                .entityType("PATIENT")
                .resourceId(patient.getId() != null ? patient.getId().toString() : null)
                .patientId(patient.getId())
                .userId(roleValidator.getCurrentUserId())
                .userName(SecurityUtils.getCurrentUsername())
                .eventDescription("Patient address changed — previous address kept as history row "
                    + saved.getId())
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit address-change audit for patient {}: {}",
                patient.getId(), ex.getMessage());
        }
    }
}
