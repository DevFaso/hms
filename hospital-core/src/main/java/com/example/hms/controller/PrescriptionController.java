package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchRequestDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchResponseDTO;
import com.example.hms.service.PrescriptionService;
import com.example.hms.service.PrescriptionSmsDispatchService;
import org.springframework.security.core.Authentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import com.example.hms.payload.dto.PharmacistVerificationRequestDTO;
import com.example.hms.service.pharmacy.PharmacistVerificationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/prescriptions")
@Tag(name = "Prescription Management", description = "APIs for managing prescriptions")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;
    private final PharmacistVerificationService pharmacistVerificationService;
    private final PrescriptionSmsDispatchService smsDispatchService;
    private final MessageSource messageSource;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_NURSE_PRACTITIONER','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Create Prescription", description = "Creates a new prescription (doctor, nurse, nurse practitioner, or hospital admin).")
    public ResponseEntity<PrescriptionResponseDTO> create(
        @Valid @RequestBody PrescriptionRequestDTO request,
        Locale locale) {
        PrescriptionResponseDTO created = prescriptionService.createPrescription(request, locale);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * The signing ceremony (P2 #16).
     *
     * <p>SIGNED used to be a status a client could simply assert, with nothing
     * on the row to contradict it. This is now the only path that sets it, and
     * it records who signed, when, and a SHA-256 digest of what they signed.
     *
     * <p>Roles are narrower than create on purpose: nurses and hospital admins
     * may draft a prescription, but signing is the prescriber's own act and the
     * service additionally requires the caller to BE the prescription's
     * prescriber. The annotation is the coarse filter; the service is the real
     * check.
     */
    @PostMapping("/{id}/sign")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE_PRACTITIONER')")
    @Operation(summary = "Sign a prescription",
        description = "Records the prescriber's signature: signer, timestamp and a digest of the "
            + "signed content. Only the prescribing clinician can sign, and only a DRAFT or "
            + "PENDING_SIGNATURE prescription can be signed.")
    public ResponseEntity<PrescriptionResponseDTO> sign(
        @PathVariable UUID id,
        Locale locale) {
        return ResponseEntity.ok(prescriptionService.signPrescription(id, locale));
    }

    /**
     * The co-sign ceremony (P2 #15). Same role note as sign: the annotation is
     * the coarse filter, and the service additionally requires the caller to be
     * a prescriber OTHER than the prescription's own. (ROLE_NURSE_PRACTITIONER
     * is kept for parity with sign but is not currently a seeded role — in
     * practice only doctors can co-sign, a known open decision.)
     */
    @PostMapping("/{id}/cosign")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE_PRACTITIONER')")
    @Operation(summary = "Co-sign a prescription that requires it",
        description = "Records a second prescriber's co-signature on a prescription flagged "
            + "requiresCosign. The co-signer must not be the prescribing clinician, and only a "
            + "DRAFT or PENDING_SIGNATURE prescription can be co-signed.")
    public ResponseEntity<PrescriptionResponseDTO> cosign(
        @PathVariable UUID id,
        Locale locale) {
        return ResponseEntity.ok(prescriptionService.cosignPrescription(id, locale));
    }

    /**
     * Tier 2 item 33 — the pharmacist-verification ceremony.
     *
     * <p>Pharmacist roles only, and deliberately NOT the prescriber roles:
     * the service refuses self-verification, but keeping doctors off the
     * endpoint entirely means the refusal is never the first thing they
     * learn about the rule.
     */
    @PostMapping("/{id}/pharmacist-verify")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_PHARMACY_VERIFIER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Verify a prescription as a pharmacist",
        description = "Records the pharmacist check that a controlled or co-sign-required "
            + "prescription needs before a nurse may administer it. Server identity and server "
            + "clock; the prescribing clinician cannot verify their own prescription; only a "
            + "SIGNED or TRANSMITTED prescription can be verified. Any later edit to the "
            + "prescription clears the verification and it must be repeated.")
    public ResponseEntity<PrescriptionResponseDTO> pharmacistVerify(
        @PathVariable UUID id,
        @RequestBody(required = false) PharmacistVerificationRequestDTO request,
        Locale locale) {
        String note = request != null ? request.getNote() : null;
        pharmacistVerificationService.verify(id, note);
        return ResponseEntity.ok(prescriptionService.getPrescriptionById(id, locale));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_PATIENT')")
    @Operation(summary = "Get Prescription by ID", description = "Fetch a prescription by ID.")
    public ResponseEntity<PrescriptionResponseDTO> getById(
        @PathVariable UUID id,
        Locale locale) {
        return ResponseEntity.ok(prescriptionService.getPrescriptionById(id, locale));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Search/List Prescriptions", description = "List prescriptions with optional filters + pagination. SUPER_ADMIN sees results across all hospitals.")
    public ResponseEntity<Page<PrescriptionResponseDTO>> list(
        @RequestParam(required = false) UUID patientId,
        @RequestParam(required = false) UUID staffId,
        @RequestParam(required = false) UUID encounterId,
        @ParameterObject Pageable pageable,
        Locale locale) {
        return ResponseEntity.ok(
            prescriptionService.list(patientId, staffId, encounterId, pageable, locale)
        );
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_NURSE_PRACTITIONER','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Update Prescription", description = "Updates an existing prescription.")
    public ResponseEntity<PrescriptionResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody PrescriptionRequestDTO request,
        Locale locale) {
        return ResponseEntity.ok(prescriptionService.updatePrescription(id, request, locale));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Hard Delete (Super Admin only)", description = "Physically deletes a prescription.")
    public ResponseEntity<String> delete(
        @PathVariable UUID id,
        Locale locale) {
        prescriptionService.deletePrescription(id, locale);
        String rawMessage = messageSource.getMessage("prescription.deleted", new Object[]{id}, locale);
        String safeMessage = HtmlUtils.htmlEscape(rawMessage);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(safeMessage);
    }

    @PostMapping("/{id}/dispatch-sms")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_NURSE_PRACTITIONER','ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Dispatch a prescription summary by SMS to a community pharmacy",
        description = "Sends a templated SMS to the chosen pharmacy's phone number and records a "
            + "PrescriptionTransmission. Pharmacy must be active at the same hospital and not "
            + "of type HOSPITAL_DISPENSARY.")
    public ResponseEntity<ApiResponseWrapper<PrescriptionSmsDispatchResponseDTO>> dispatchSms(
        Authentication auth,
        @PathVariable UUID id,
        @Valid @RequestBody PrescriptionSmsDispatchRequestDTO request) {
        PrescriptionSmsDispatchResponseDTO result = smsDispatchService.dispatch(auth, id, request);
        return ResponseEntity.ok(ApiResponseWrapper.success(result));
    }
}
