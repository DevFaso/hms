package com.example.hms.controller;

import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.referral.RejectReferralRequestDTO;
import com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.service.ReferralExpiryService;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for general referral management (multi-specialty)
 */
@RestController
@RequestMapping("/referrals")
@RequiredArgsConstructor
@Tag(name = "General Referral Management", description = "Multi-specialty referral system")
public class GeneralReferralController {

    private final GeneralReferralService referralService;
    private final ReferralExpiryService referralExpiryService;

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

    @PostMapping("/{referralId}/schedule")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR')")
    @Operation(summary = "Schedule referral", description = "Receiving provider schedules an appointment")
    public ResponseEntity<GeneralReferralResponseDTO> scheduleReferral(
        @PathVariable UUID referralId,
        @Valid @RequestBody ScheduleReferralRequestDTO request
    ) {
        return ResponseEntity.ok(referralService.scheduleReferral(referralId, request));
    }

    @PostMapping("/{referralId}/start")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR')")
    @Operation(summary = "Start referral", description = "Mark referral consultation as in progress")
    public ResponseEntity<GeneralReferralResponseDTO> startReferral(@PathVariable UUID referralId) {
        return ResponseEntity.ok(referralService.startReferral(referralId));
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

    @PostMapping("/{referralId}/reject")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR')")
    @Operation(summary = "Reject referral", description = "Receiving provider declines the referral")
    public ResponseEntity<GeneralReferralResponseDTO> rejectReferral(
        @PathVariable UUID referralId,
        @Valid @RequestBody RejectReferralRequestDTO request
    ) {
        return ResponseEntity.ok(referralService.rejectReferral(referralId, request));
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
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get referrals by hospital", description = "Retrieve referrals for hospital with optional status filter")
    public ResponseEntity<List<GeneralReferralResponseDTO>> getReferralsByHospital(
        @PathVariable UUID hospitalId,
        @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(referralService.getReferralsByHospital(hospitalId, status));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    @Operation(summary = "List all referrals")
    public ResponseEntity<List<GeneralReferralResponseDTO>> getAllReferrals(
        @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(referralService.getAllReferrals(status));
    }

    @GetMapping("/overdue")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get overdue referrals", description = "Retrieve all overdue referrals")
    public ResponseEntity<List<GeneralReferralResponseDTO>> getOverdueReferrals() {
        return ResponseEntity.ok(referralService.getOverdueReferrals());
    }

    @PostMapping("/admin/expire-overdue")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "Manually trigger the EXPIRED auto-sweep",
        description = "Transition every SUBMITTED/ACKNOWLEDGED/SCHEDULED referral whose SLA "
            + "fell before now() - graceHours to EXPIRED. Mirrors the @Scheduled sweep so "
            + "operators can run it ad-hoc when the scheduler is OFF or after extended downtime."
    )
    public ResponseEntity<Map<String, Integer>> expireOverdueReferrals(
        @RequestParam(name = "graceHours", required = false, defaultValue = "0") long graceHours
    ) {
        long bounded = Math.max(0L, graceHours);
        int expired = referralExpiryService.expireOverdueReferrals(Duration.ofHours(bounded));
        return ResponseEntity.ok(Map.of("expired", expired));
    }
}
