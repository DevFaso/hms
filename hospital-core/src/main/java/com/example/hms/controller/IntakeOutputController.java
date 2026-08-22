package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.IntakeOutputEntryRequestDTO;
import com.example.hms.payload.dto.IntakeOutputSummaryDTO;
import com.example.hms.service.IntakeOutputService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fluid intake/output entries + window totals (P3 #18, flowsheets/I&O).
 *
 * <p>Roles mirror the vitals endpoints. NOTE: for GETs under /patients the
 * broad {@code GET /patients/**} SecurityConfig matcher wins first-match, so
 * the {@code @PreAuthorize} here is the authoritative gate, not hardening.
 * The catalog permission RECORD_INTAKE_OUTPUT remains declared-but-unused —
 * every nursing endpoint in this codebase is role-gated, and diverging for
 * one controller would fork the model; consuming the catalog is a standing
 * decision for a dedicated pass.
 */
@RestController
@RequestMapping("/patients/{patientId}/intake-output")
@RequiredArgsConstructor
@Tag(name = "Intake / Output", description = "Fluid intake and output entries with window totals")
public class IntakeOutputController {

    private static final String READ_WRITE_ROLES =
        "hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final IntakeOutputService intakeOutputService;
    private final ControllerAuthUtils authUtils;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(READ_WRITE_ROLES)
    @Operation(
        summary = "Record a fluid intake/output entry",
        description = "The route determines the category (intake vs output) server-side.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<IntakeOutputSummaryDTO.Entry> recordEntry(
        @PathVariable UUID patientId,
        @Valid @RequestBody IntakeOutputEntryRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        UUID recorderUserId = authUtils.resolveUserId(auth).orElse(null);
        IntakeOutputSummaryDTO.Entry created =
            intakeOutputService.recordEntry(patientId, resolvedHospitalId, recorderUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @PreAuthorize(READ_WRITE_ROLES)
    @Operation(
        summary = "Fluid-balance summary for a window",
        description = "Entries plus server-computed intake/output totals and balance. "
            + "from/to default to the last 24 hours.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<IntakeOutputSummaryDTO> getSummary(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        LocalDateTime fromDate = authUtils.parseDateTime(from);
        LocalDateTime toDate = authUtils.parseDateTime(to);
        return ResponseEntity.ok(
            intakeOutputService.getSummary(patientId, resolvedHospitalId, fromDate, toDate));
    }
}
