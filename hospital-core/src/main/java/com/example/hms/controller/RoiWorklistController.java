package com.example.hms.controller;

import com.example.hms.enums.RoiRequestStatus;
import com.example.hms.payload.dto.roi.RoiDecisionDTO;
import com.example.hms.payload.dto.roi.RoiRequestResponseDTO;
import com.example.hms.service.roi.RoiRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The release-of-information worklist and decisions (Tier 2 item 39b).
 * Intake lives on the patient-scoped controller; this one is triage.
 * Decisions are tightened to the roles that answer for a release —
 * fulfilling puts a record copy in an outside hand, denying is an outcome
 * the requester can contest — while the worklist itself is readable by the
 * same set that can log a request.
 */
@RestController
@RequestMapping(value = "/roi-requests", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR',"
    + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
@Tag(name = "Release of Information", description = "Triage worklist and decisions.")
@SecurityRequirement(name = "bearerAuth")
public class RoiWorklistController {

    private static final String DECISION_ROLES =
        "hasAnyAuthority('ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final RoiRequestService roiService;

    @GetMapping
    @Operation(summary = "The hospital's requests in one status, oldest first",
        description = "Defaults to PENDING — the triage queue.")
    public ResponseEntity<Page<RoiRequestResponseDTO>> worklist(
        @RequestParam(required = false) RoiRequestStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(roiService.worklist(status, page, size));
    }

    @PutMapping("/{requestId}/fulfil")
    @PreAuthorize(DECISION_ROLES)
    @Operation(summary = "Fulfil a pending request",
        description = "Marks the request FULFILLED and emits the PATIENT_EXPORT disclosure row "
            + "the patient's own report shows as COPY_RELEASED. The actual copy is produced "
            + "through the chart's record download; this records that it was released.")
    public ResponseEntity<RoiRequestResponseDTO> fulfil(
        @PathVariable UUID requestId,
        @Valid @RequestBody RoiDecisionDTO decision) {
        return ResponseEntity.ok(roiService.fulfil(requestId, decision));
    }

    @PutMapping("/{requestId}/deny")
    @PreAuthorize(DECISION_ROLES)
    @Operation(summary = "Deny a pending request",
        description = "Needs a reason — it is the outcome the requester is told. Denials do "
            + "not appear on the patient's disclosure report: nothing was disclosed.")
    public ResponseEntity<RoiRequestResponseDTO> deny(
        @PathVariable UUID requestId,
        @Valid @RequestBody RoiDecisionDTO decision) {
        return ResponseEntity.ok(roiService.deny(requestId, decision));
    }

    @PutMapping("/{requestId}/cancel")
    @Operation(summary = "Cancel a pending request",
        description = "For requests withdrawn by the requester or logged in error.")
    public ResponseEntity<RoiRequestResponseDTO> cancel(
        @PathVariable UUID requestId,
        @Valid @RequestBody RoiDecisionDTO decision) {
        return ResponseEntity.ok(roiService.cancel(requestId, decision));
    }
}
