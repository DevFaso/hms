package com.example.hms.controller;

import com.example.hms.enums.SignatureType;
import com.example.hms.payload.dto.signature.SignatureAuditEntryDTO;
import com.example.hms.payload.dto.signature.SignatureRequestDTO;
import com.example.hms.payload.dto.signature.SignatureResponseDTO;
import com.example.hms.payload.dto.signature.SignatureRevocationRequestDTO;
import com.example.hms.payload.dto.signature.SignatureVerificationRequestDTO;
import com.example.hms.payload.dto.signature.SignatureVerificationResponseDTO;
import com.example.hms.service.signature.DigitalSignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
 * REST controller for digital signature operations.
 * Story #17: Generic Report Signing API
 */
@RestController
@RequestMapping("/signatures")
@RequiredArgsConstructor
@Tag(name = "Digital Signatures", description = "APIs for signing and verifying clinical reports")
public class DigitalSignatureController {

    private final DigitalSignatureService signatureService;

    /**
     * Sign a clinical report
     */
    @PostMapping("/sign")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_SCIENTIST', 'PHARMACIST')")
    @Operation(summary = "Sign a clinical report", 
        description = "Create an electronic signature on a clinical report (discharge summary, lab result, imaging report, etc.)")
    public ResponseEntity<SignatureResponseDTO> signReport(@Valid @RequestBody SignatureRequestDTO request) {
        SignatureResponseDTO response = signatureService.signReport(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Verify a signature
     */
    @PostMapping("/verify")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_SCIENTIST', 'PHARMACIST', 'HOSPITAL_ADMIN', 'RECEPTIONIST')")
    @Operation(summary = "Verify a digital signature", 
        description = "Verify that a signature value matches a stored signature for a report")
    public ResponseEntity<SignatureVerificationResponseDTO> verifySignature(
            @Valid @RequestBody SignatureVerificationRequestDTO request) {
        SignatureVerificationResponseDTO response = signatureService.verifySignature(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Revoke a signature
     */
    @PostMapping("/{signatureId}/revoke")
    @PreAuthorize("hasAnyRole('DOCTOR', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Revoke a digital signature", 
        description = "Revoke an existing signature (requires elevated permissions)")
    public ResponseEntity<SignatureResponseDTO> revokeSignature(
            @PathVariable UUID signatureId,
            @Valid @RequestBody SignatureRevocationRequestDTO request) {
        SignatureResponseDTO response = signatureService.revokeSignature(signatureId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get signatures for a specific report
     */
    @GetMapping("/report/{reportType}/{reportId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_SCIENTIST', 'PHARMACIST', 'HOSPITAL_ADMIN', 'RECEPTIONIST')")
    @Operation(summary = "Get all signatures for a report", 
        description = "Retrieve all digital signatures associated with a specific clinical report")
    public ResponseEntity<List<SignatureResponseDTO>> getSignaturesByReport(
            @PathVariable SignatureType reportType,
            @PathVariable UUID reportId) {
        List<SignatureResponseDTO> signatures = signatureService.getSignaturesByReportId(reportType, reportId);
        return ResponseEntity.ok(signatures);
    }

    /**
     * Get signatures by provider
     */
    @GetMapping("/provider/{providerId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_SCIENTIST', 'PHARMACIST', 'HOSPITAL_ADMIN')")
    @Operation(summary = "Get signatures by provider", 
        description = "Retrieve all signatures created by a specific healthcare provider")
    public ResponseEntity<List<SignatureResponseDTO>> getSignaturesByProvider(@PathVariable UUID providerId) {
        List<SignatureResponseDTO> signatures = signatureService.getSignaturesByProvider(providerId);
        return ResponseEntity.ok(signatures);
    }

    /**
     * Get a specific signature by ID
     */
    @GetMapping("/{signatureId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_SCIENTIST', 'PHARMACIST', 'HOSPITAL_ADMIN', 'RECEPTIONIST')")
    @Operation(summary = "Get signature by ID", 
        description = "Retrieve details of a specific digital signature")
    public ResponseEntity<SignatureResponseDTO> getSignatureById(@PathVariable UUID signatureId) {
        SignatureResponseDTO signature = signatureService.getSignatureById(signatureId);
        return ResponseEntity.ok(signature);
    }

    /**
     * Get audit trail for a signature
     */
    @GetMapping("/{signatureId}/audit-trail")
    @PreAuthorize("hasAnyRole('DOCTOR', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get signature audit trail", 
        description = "Retrieve complete audit trail showing all actions performed on a signature")
    public ResponseEntity<List<SignatureAuditEntryDTO>> getSignatureAuditTrail(@PathVariable UUID signatureId) {
        List<SignatureAuditEntryDTO> auditTrail = signatureService.getSignatureAuditTrail(signatureId);
        return ResponseEntity.ok(auditTrail);
    }

    /**
     * Check if a report is signed
     */
    @GetMapping("/report/{reportType}/{reportId}/is-signed")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_SCIENTIST', 'PHARMACIST', 'HOSPITAL_ADMIN', 'RECEPTIONIST')")
    @Operation(summary = "Check if report is signed", 
        description = "Check whether a report has at least one valid signature")
    public ResponseEntity<Boolean> isReportSigned(
            @PathVariable SignatureType reportType,
            @PathVariable UUID reportId) {
        boolean isSigned = signatureService.isReportSigned(reportType, reportId);
        return ResponseEntity.ok(isSigned);
    }
}
