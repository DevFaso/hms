package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.recordaccess.OptOutRequestDTO;
import com.example.hms.payload.dto.recordaccess.RecordAccessDecisionDTO;
import com.example.hms.payload.dto.recordaccess.RecordSharingOptOutDTO;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.RecordSharingOptOutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/**
 * E8 #48 / #52 — the treatment-relationship decision, and the patient's
 * opt-out from cross-hospital sharing.
 *
 * <p>{@code GET .../record-access} exists so the resolver can be exercised on
 * a real environment before the read filter (#49) consumes it: open a patient
 * at hospital B, call this, and the answer names the carrier or the gate that
 * refused. It is audited as a patient access by the interceptor's convention
 * (path variable {@code patientId}) — deliberately, since "is this patient on
 * your schedule here?" is itself a fact about the patient.
 */
@Tag(name = "Record access", description = "Treatment-relationship access and the patient's sharing opt-out (E8)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/patients/{patientId}")
public class RecordAccessController {

    static final String CLINICAL_ROLES = "hasAnyAuthority("
        + "'ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_LAB_SCIENTIST',"
        + "'ROLE_LAB_TECHNICIAN','ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";
    static final String OPT_OUT_ROLES = "hasAnyAuthority("
        + "'ROLE_PATIENT','ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final RecordAccessPolicy recordAccessPolicy;
    private final RecordSharingOptOutService optOutService;
    private final PatientRepository patientRepository;
    private final ControllerAuthUtils authUtils;

    @Operation(summary = "May the caller, at their active hospital, read this patient's chart from other hospitals?",
        description = "Answers with the carrier that establishes the treatment relationship, or the first gate that refused.")
    @GetMapping("/record-access")
    @PreAuthorize(CLINICAL_ROLES)
    public ResponseEntity<RecordAccessDecisionDTO> decide(@PathVariable UUID patientId, Authentication auth) {
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        UUID hospitalId = authUtils.resolveHospitalScope(auth, (UUID) null, false);
        return ResponseEntity.ok(RecordAccessDecisionDTO.from(
            recordAccessPolicy.decide(actorUserId, patientId, hospitalId)));
    }

    @Operation(summary = "Is this patient's record excluded from cross-hospital reads?")
    @GetMapping("/record-sharing/opt-out")
    @PreAuthorize(OPT_OUT_ROLES)
    public ResponseEntity<RecordSharingOptOutDTO> optOutStatus(
            @PathVariable UUID patientId,
            @RequestHeader(name = "Accept-Language", required = false) String lang,
            Authentication auth) {
        requireSelfIfPatient(patientId, auth);
        return ResponseEntity.ok(optOutService.status(patientId, parseLocale(lang)));
    }

    @Operation(summary = "Exclude this patient's record from cross-hospital reads",
        description = "Their own hospital's access is untouched. Idempotent by refusal: a second opt-out is 409.")
    @WriteAudited(skip = true, reason = "service emits CONSENT_UPDATE after commit")
    @PostMapping("/record-sharing/opt-out")
    @PreAuthorize(OPT_OUT_ROLES)
    public ResponseEntity<RecordSharingOptOutDTO> optOut(
            @PathVariable UUID patientId,
            @Valid @RequestBody(required = false) OptOutRequestDTO body,
            @RequestHeader(name = "Accept-Language", required = false) String lang,
            Authentication auth) {
        requireSelfIfPatient(patientId, auth);
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        String reason = body == null ? null : body.reason();
        return ResponseEntity.ok(optOutService.optOut(patientId, reason, actorUserId, parseLocale(lang)));
    }

    @Operation(summary = "Revoke the opt-out; the row stays for the disclosure report")
    @WriteAudited(skip = true, reason = "service emits CONSENT_UPDATE after commit")
    @DeleteMapping("/record-sharing/opt-out")
    @PreAuthorize(OPT_OUT_ROLES)
    public ResponseEntity<RecordSharingOptOutDTO> revoke(
            @PathVariable UUID patientId,
            @RequestHeader(name = "Accept-Language", required = false) String lang,
            Authentication auth) {
        requireSelfIfPatient(patientId, auth);
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok(optOutService.revoke(patientId, actorUserId, parseLocale(lang)));
    }

    /**
     * A patient may act only on their own record. Staff roles pass. A caller
     * who holds ROLE_PATIENT and nothing staff-shaped, and whose patient row
     * is not this one, is refused with 403 — never 404, since the patient id
     * in the path is theirs to know or not.
     */
    private void requireSelfIfPatient(UUID patientId, Authentication auth) {
        boolean staff = authUtils.hasAuthority(auth, "ROLE_RECEPTIONIST")
            || authUtils.hasAuthority(auth, "ROLE_HOSPITAL_ADMIN")
            || authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN");
        if (staff) return;
        UUID userId = authUtils.resolveUserId(auth).orElse(null);
        boolean own = userId != null && patientRepository.findByUserId(userId)
            .map(p -> patientId.equals(p.getId()))
            .orElse(false);
        if (!own) {
            throw new AccessDeniedException("A patient may only manage their own record-sharing opt-out.");
        }
    }

    private static Locale parseLocale(String lang) {
        return (lang == null || lang.isBlank()) ? Locale.ENGLISH : Locale.forLanguageTag(lang.split(",")[0].trim());
    }
}
