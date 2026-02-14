package com.example.hms.controller;

import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.service.GeneralReferralService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for general referral management (multi-specialty)
 */
@RestController
@RequestMapping("/api/referrals")
@RequiredArgsConstructor
@Tag(name = "General Referral Management", description = "Multi-specialty referral system")
public class GeneralReferralController {

    private final GeneralReferralService referralService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE')")
    @Operation(summary = "Create referral", description = "Create a new multi-specialty referral")
    public ResponseEntity<GeneralReferralResponseDTO> createReferral(@Valid @RequestBody GeneralReferralRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(referralService.createReferral(request));
    }

    @GetMapping("/{referralId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get referral", description = "Retrieve referral by ID")
    public ResponseEntity<GeneralReferralResponseDTO> getReferral(@PathVariable UUID referralId) {
        return ResponseEntity.ok(referralService.getReferral(referralId));
    }

    @PostMapping("/{referralId}/submit")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE')")
    @Operation(summary = "Submit referral", description = "Submit referral to receiving provider")
    public ResponseEntity<GeneralReferralResponseDTO> submitReferral(@PathVariable UUID referralId) {
        return ResponseEntity.ok(referralService.submitReferral(referralId));
    }

    @PostMapping("/{referralId}/acknowledge")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR')")
    @Operation(summary = "Acknowledge referral", description = "Receiving provider acknowledges referral")
    public ResponseEntity<GeneralReferralResponseDTO> acknowledgeReferral(
        @PathVariable UUID referralId,
        @RequestParam String notes,
        @RequestParam UUID receivingProviderId
    ) {
        return ResponseEntity.ok(referralService.acknowledgeReferral(referralId, notes, receivingProviderId));
    }

    @PostMapping("/{referralId}/complete")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR')")
    @Operation(summary = "Complete referral", description = "Mark referral as completed with summary")
    public ResponseEntity<GeneralReferralResponseDTO> completeReferral(
        @PathVariable UUID referralId,
        @RequestParam String summary,
        @RequestParam(required = false) String followUp
    ) {
        return ResponseEntity.ok(referralService.completeReferral(referralId, summary, followUp));
    }

    @PostMapping("/{referralId}/cancel")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Cancel referral", description = "Cancel referral with reason")
    public ResponseEntity<Void> cancelReferral(
        @PathVariable UUID referralId,
        @RequestParam String reason
    ) {
        referralService.cancelReferral(referralId, reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get referrals by patient", description = "Retrieve all referrals for a patient")
    public ResponseEntity<List<GeneralReferralResponseDTO>> getReferralsByPatient(@PathVariable UUID patientId) {
        return ResponseEntity.ok(referralService.getReferralsByPatient(patientId));
    }

    @GetMapping("/provider/{providerId}/referring")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE')")
    @Operation(summary = "Get referrals made by provider", description = "Retrieve referrals created by provider")
    public ResponseEntity<List<GeneralReferralResponseDTO>> getReferralsByReferringProvider(@PathVariable UUID providerId) {
        return ResponseEntity.ok(referralService.getReferralsByReferringProvider(providerId));
    }

    @GetMapping("/provider/{providerId}/receiving")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR')")
    @Operation(summary = "Get referrals to provider", description = "Retrieve referrals sent to provider")
    public ResponseEntity<List<GeneralReferralResponseDTO>> getReferralsByReceivingProvider(@PathVariable UUID providerId) {
        return ResponseEntity.ok(referralService.getReferralsByReceivingProvider(providerId));
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get referrals by hospital", description = "Retrieve referrals for hospital with optional status filter")
    public ResponseEntity<List<GeneralReferralResponseDTO>> getReferralsByHospital(
        @PathVariable UUID hospitalId,
        @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(referralService.getReferralsByHospital(hospitalId, status));
    }

    @GetMapping("/overdue")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get overdue referrals", description = "Retrieve all overdue referrals")
    public ResponseEntity<List<GeneralReferralResponseDTO>> getOverdueReferrals() {
        return ResponseEntity.ok(referralService.getOverdueReferrals());
    }
}
