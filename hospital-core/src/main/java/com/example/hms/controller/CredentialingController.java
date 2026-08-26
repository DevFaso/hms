package com.example.hms.controller;

import com.example.hms.model.StaffCredentialRenewal;
import com.example.hms.model.User;
import com.example.hms.payload.dto.CredentialRenewalRequestDTO;
import com.example.hms.payload.dto.CredentialRenewalResponseDTO;
import com.example.hms.service.credentialing.CredentialRenewalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Provider credentialing (Tier 2 item 40).
 *
 * <p>Administrator roles only, and deliberately not the clinical roles. The
 * service refuses a practitioner recording their own renewal, but keeping
 * clinicians off the endpoint entirely means that refusal is never the first
 * thing they learn about the rule — the same reasoning that keeps prescribers
 * off {@code /pharmacist-verify}.
 */
@RestController
@RequestMapping("/staff")
@Tag(name = "Credentialing",
    description = "Practising-licence renewal and history for hospital staff.")
@RequiredArgsConstructor
public class CredentialingController {

    private final CredentialRenewalService credentialRenewalService;

    @PostMapping("/{staffId}/credentials/renew")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Record a credential renewal",
        description = "Moves the practitioner's licence expiry forward and appends a history "
            + "row. Server identity and server clock; a practitioner cannot record their own "
            + "renewal; 404-not-403 across hospitals. Records only — an expired licence still "
            + "does not block clinical work.")
    public ResponseEntity<CredentialRenewalResponseDTO> renew(
        @PathVariable UUID staffId,
        @Valid @RequestBody CredentialRenewalRequestDTO request) {
        StaffCredentialRenewal saved = credentialRenewalService.recordRenewal(
            staffId,
            request.getExpiryDate(),
            request.getLicenseNumber(),
            request.getIssuingAuthority(),
            request.getNote());
        return ResponseEntity.ok(toDto(saved));
    }

    @GetMapping("/{staffId}/credentials/history")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Credential renewal history",
        description = "Every renewal recorded for this practitioner, newest first. Exists so "
            + "\"was this clinician licensed on the day they prescribed that\" has an answer.")
    public ResponseEntity<List<CredentialRenewalResponseDTO>> history(@PathVariable UUID staffId) {
        return ResponseEntity.ok(
            credentialRenewalService.history(staffId).stream().map(CredentialingController::toDto).toList());
    }

    private static CredentialRenewalResponseDTO toDto(StaffCredentialRenewal r) {
        return CredentialRenewalResponseDTO.builder()
            .id(r.getId())
            .staffId(r.getStaff() != null ? r.getStaff().getId() : null)
            .previousLicenseNumber(r.getPreviousLicenseNumber())
            .previousExpiryDate(r.getPreviousExpiryDate())
            .licenseNumber(r.getLicenseNumber())
            .expiryDate(r.getExpiryDate())
            .issuingAuthority(r.getIssuingAuthority())
            .note(r.getNote())
            .recordedByUserId(r.getRecordedBy() != null ? r.getRecordedBy().getId() : null)
            .recordedByName(displayName(r.getRecordedBy()))
            .recordedAt(r.getRecordedAt())
            .build();
    }

    /**
     * A name, falling back to the login handle only when nothing else is
     * recorded — "renewed by hadmin1" tells an auditor less than a person.
     */
    private static String displayName(User user) {
        if (user == null) {
            return null;
        }
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
