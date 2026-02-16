package com.example.hms.controller;

import com.example.hms.payload.dto.referral.ObgynReferralAcknowledgeRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralCancelRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralCompletionRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralCreateRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralMessageDTO;
import com.example.hms.payload.dto.referral.ObgynReferralMessageRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralResponseDTO;
import com.example.hms.payload.dto.referral.ReferralStatusSummaryDTO;
import com.example.hms.service.ObgynReferralService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/referrals/obgyn")
@RequiredArgsConstructor
@Validated
@Tag(name = "OB-GYN Referrals", description = "Midwife to OB-GYN referral workflow")
public class ObgynReferralController {

    private final ObgynReferralService referralService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN','MANAGE_OBGYN_REFERRAL')")
    @Operation(summary = "Create OB-GYN referral")
    public ResponseEntity<ObgynReferralResponseDTO> createReferral(
        @Valid @RequestBody ObgynReferralCreateRequestDTO request,
        Authentication authentication
    ) {
        ObgynReferralResponseDTO response = referralService.createReferral(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_NURSE','ROLE_SUPER_ADMIN','VIEW_OBGYN_REFERRAL')")
    @Operation(summary = "Get referral detail")
    public ResponseEntity<ObgynReferralResponseDTO> getReferral(@PathVariable UUID id) {
        return ResponseEntity.ok(referralService.getReferral(id));
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_NURSE','ROLE_SUPER_ADMIN','VIEW_OBGYN_REFERRAL')")
    @Operation(summary = "List referrals for patient")
    public ResponseEntity<Page<ObgynReferralResponseDTO>> getReferralsForPatient(
        @PathVariable UUID patientId,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(referralService.getReferralsForPatient(patientId, pageable));
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','VIEW_OBGYN_REFERRAL')")
    @Operation(summary = "List referrals for hospital")
    public ResponseEntity<Page<ObgynReferralResponseDTO>> getReferralsForHospital(
        @PathVariable UUID hospitalId,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(referralService.getReferralsForHospital(hospitalId, pageable));
    }

    @GetMapping("/assigned/{obgynUserId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_SUPER_ADMIN','VIEW_OBGYN_REFERRAL')")
    @Operation(summary = "List referrals assigned to OB-GYN")
    public ResponseEntity<Page<ObgynReferralResponseDTO>> getReferralsForObgyn(
        @PathVariable UUID obgynUserId,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(referralService.getReferralsForObgyn(obgynUserId, pageable));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_SUPER_ADMIN','MANAGE_OBGYN_REFERRAL')")
    @Operation(summary = "Acknowledge referral")
    public ResponseEntity<ObgynReferralResponseDTO> acknowledgeReferral(
        @PathVariable UUID id,
        @Valid @RequestBody ObgynReferralAcknowledgeRequestDTO request,
        Authentication authentication
    ) {
        return ResponseEntity.ok(referralService.acknowledgeReferral(id, request, authentication.getName()));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_SUPER_ADMIN','MANAGE_OBGYN_REFERRAL')")
    @Operation(summary = "Complete referral")
    public ResponseEntity<ObgynReferralResponseDTO> completeReferral(
        @PathVariable UUID id,
        @Valid @RequestBody ObgynReferralCompletionRequestDTO request,
        Authentication authentication
    ) {
        return ResponseEntity.ok(referralService.completeReferral(id, request, authentication.getName()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('ROLE_MIDWIFE','ROLE_SUPER_ADMIN','MANAGE_OBGYN_REFERRAL')")
    @Operation(summary = "Cancel referral")
    public ResponseEntity<ObgynReferralResponseDTO> cancelReferral(
        @PathVariable UUID id,
        @Valid @RequestBody ObgynReferralCancelRequestDTO request,
        Authentication authentication
    ) {
        return ResponseEntity.ok(referralService.cancelReferral(id, request, authentication.getName()));
    }

    @PostMapping("/{id}/messages")
    @PreAuthorize("hasAnyAuthority('ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN','MANAGE_OBGYN_REFERRAL')")
    @Operation(summary = "Add threaded message")
    public ResponseEntity<ObgynReferralMessageDTO> addMessage(
        @PathVariable UUID id,
        @Valid @RequestBody ObgynReferralMessageRequestDTO request,
        Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(referralService.addMessage(id, request, authentication.getName()));
    }

    @GetMapping("/{id}/messages")
    @PreAuthorize("hasAnyAuthority('ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_NURSE','ROLE_SUPER_ADMIN','VIEW_OBGYN_REFERRAL')")
    @Operation(summary = "List messages for referral")
    public ResponseEntity<List<ObgynReferralMessageDTO>> getMessages(@PathVariable UUID id) {
        return ResponseEntity.ok(referralService.getMessages(id));
    }

    @GetMapping("/reports/summary")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_NURSE','VIEW_OBGYN_REFERRAL')")
    @Operation(summary = "Referral KPI summary")
    public ResponseEntity<ReferralStatusSummaryDTO> getSummary() {
        return ResponseEntity.ok(referralService.getStatusSummary());
    }
}
