package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchRequestDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchResponseDTO;
import com.example.hms.service.PrescriptionReaderRoles;
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
import com.example.hms.payload.dto.PrescriptionClarificationRequestDTO;
import com.example.hms.payload.dto.PrescriptionClarificationResolutionDTO;
import com.example.hms.service.pharmacy.PharmacistVerificationService;
import com.example.hms.service.pharmacy.PrescriptionClarificationService;
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
    private final PrescriptionClarificationService clarificationService;
    private final PrescriptionSmsDispatchService smsDispatchService;
    private final MessageSource messageSource;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    @Operation(summary = "Create Prescription", description = "Creates a new prescription (doctor, nurse or midwife).")
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
    @PreAuthorize("hasAuthority('ROLE_DOCTOR')")
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
     * a prescriber OTHER than the prescription's own. Only doctors can
     * co-sign: E9 #68 took the never-seeded nurse-practitioner role off
     * every guard, so the annotation now says what always held.
     */
    @PostMapping("/{id}/cosign")
    @PreAuthorize("hasAuthority('ROLE_DOCTOR')")
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
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_PHARMACY_VERIFIER','ROLE_SUPER_ADMIN')")
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
        return ResponseEntity.ok(prescriptionService.getPrescriptionAfterWrite(id, locale));
    }

    /**
     * Gap G5 — the pharmacist sends an order back with a question.
     *
     * <p>Pharmacist roles, mirroring {@link #pharmacistVerify}. No
     * {@code SecurityConfig} matcher covers {@code /prescriptions/**}: the
     * path rides {@code anyRequest().authenticated()} and this annotation is
     * the gate, so the two cannot disagree (PrescriptionControllerTest pins
     * the absence of a matcher).
     */
    @PostMapping("/{id}/request-clarification")
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST','ROLE_PHARMACY_VERIFIER','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Send a prescription back to its prescriber for clarification",
        description = "Moves a prescription awaiting a fill to PENDING_CLARIFICATION with the "
            + "pharmacist's reason, takes it off the work queue, and notifies the prescriber. "
            + "Only a SIGNED, TRANSMITTED, PARTIALLY_FILLED, PENDING_STOCK or PARTNER_REJECTED "
            + "prescription can be sent back.")
    public ResponseEntity<PrescriptionResponseDTO> requestClarification(
        @PathVariable UUID id,
        @Valid @RequestBody PrescriptionClarificationRequestDTO request,
        Locale locale) {
        clarificationService.requestClarification(id, request.getReason());
        return ResponseEntity.ok(prescriptionService.getPrescriptionAfterWrite(id, locale));
    }

    /**
     * Gap G5 — a doctor at the prescribing hospital answers and the order
     * returns to SIGNED. Doctors only, as for sign and co-sign; the service
     * additionally requires a staff profile at the prescription's hospital.
     */
    @PostMapping("/{id}/resolve-clarification")
    @PreAuthorize("hasAuthority('ROLE_DOCTOR')")
    @Operation(summary = "Resolve a pharmacist's clarification request",
        description = "Records the prescriber's answer and returns a PENDING_CLARIFICATION "
            + "prescription to SIGNED so the pharmacy can fill or route it. Any doctor with a "
            + "staff profile at the prescribing hospital may answer; the answer is optional when "
            + "the order itself was edited.")
    public ResponseEntity<PrescriptionResponseDTO> resolveClarification(
        @PathVariable UUID id,
        @Valid @RequestBody(required = false) PrescriptionClarificationResolutionDTO request,
        Locale locale) {
        clarificationService.resolveClarification(id, request != null ? request.getResponse() : null);
        return ResponseEntity.ok(prescriptionService.getPrescriptionAfterWrite(id, locale));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_PATIENT')")
    @Operation(summary = "Get Prescription by ID", description = "Fetch a prescription by ID. A patient "
        + "receives their copy without the pharmacist-to-prescriber clarification exchange.")
    public ResponseEntity<PrescriptionResponseDTO> getById(
        @PathVariable UUID id,
        Authentication auth,
        Locale locale) {
        PrescriptionResponseDTO dto = prescriptionService.getPrescriptionById(id, locale);
        if (isPatientOnly(auth)) {
            dto = dto.withoutClarificationExchange();
        }
        return ResponseEntity.ok(dto);
    }

    /**
     * The same patient-copy rule as {@code /me/patient/prescriptions} (gap
     * G7): a principal that holds ROLE_PATIENT and no clinical reader role
     * gets the copy without the clarification exchange.
     *
     * <p>The rule itself now lives in {@link PrescriptionReaderRoles} because
     * {@code PrescriptionServiceImpl.getPrescriptionById} decides the same
     * question — whether this caller may read anyone's prescription or only
     * their own — and the two must not drift apart.
     */
    static boolean isPatientOnly(Authentication auth) {
        return PrescriptionReaderRoles.isPatientOnly(auth);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_SUPER_ADMIN')")
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
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
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
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST')")
    @Operation(summary = "Hand a prescription to a community or partner pharmacy by SMS",
        description = """
            Sends the pharmacy the prescription summary together with a reference token and the \
            reply to send back ("« 1 <REF> » pour accepter, « 2 <REF> » pour refuser"), records a \
            PrescriptionTransmission, creates a PENDING PARTNER PrescriptionRoutingDecision and \
            moves the prescription to SENT_TO_PARTNER — so it leaves the in-house dispense queue \
            and cannot be filled twice. The pharmacy's reply arrives on POST /webhooks/partner-sms \
            and is matched on the reference AND the sending number; the 2 h reminder and 4 h \
            auto-reject sweep then apply.

            The pharmacy must be active, at the prescription's hospital, have a phone number on \
            file and not be of type HOSPITAL_DISPENSARY. The prescription must be SIGNED, \
            TRANSMITTED, PARTNER_REJECTED or PENDING_STOCK: a refusal or a back order can be sent \
            to another pharmacy, and doing so supersedes the offer the previous pharmacy held \
            (its reference stops working and it is told).""")
    public ResponseEntity<ApiResponseWrapper<PrescriptionSmsDispatchResponseDTO>> dispatchSms(
        Authentication auth,
        @PathVariable UUID id,
        @Valid @RequestBody PrescriptionSmsDispatchRequestDTO request) {
        PrescriptionSmsDispatchResponseDTO result = smsDispatchService.dispatch(auth, id, request);
        return ResponseEntity.ok(ApiResponseWrapper.success(result));
    }
}
