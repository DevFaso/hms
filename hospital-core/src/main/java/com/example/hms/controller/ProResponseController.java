package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.pro.ProAcknowledgeRequestDTO;
import com.example.hms.payload.dto.pro.ProResponseCreateDTO;
import com.example.hms.payload.dto.pro.ProResponseDTO;
import com.example.hms.service.pro.ProResponseService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Administered instruments for one patient (Tier 2 item 47). Same roles as
 * the postpartum module it hangs on.
 */
@RestController
@RequestMapping("/patients/{patientId}/pro-responses")
@RequiredArgsConstructor
@Tag(name = "PRO Responses", description = "Administer standardized instruments and act on the results")
public class ProResponseController {

    private static final String CLINICAL_ROLES =
        "hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final ProResponseService responseService;
    private final ControllerAuthUtils authUtils;

    @PostMapping
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Record an administered instrument",
        description = "Scores server-side from the instrument's option scores, links the response to the "
            + "patient's open postpartum plan, and alerts the care team on a positive screen or a "
            + "safety-item answer.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProResponseDTO> record(@PathVariable UUID patientId,
                                                 @Valid @RequestBody ProResponseCreateDTO request,
                                                 @RequestParam(required = false) UUID hospitalId,
                                                 Authentication auth) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, request.getHospitalId(), false);
        request.setHospitalId(scope);
        return ResponseEntity.status(HttpStatus.CREATED).body(responseService.record(patientId, request));
    }

    @GetMapping
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "The patient's responses for one instrument, newest first",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<ProResponseDTO>> history(@PathVariable UUID patientId,
                                                        @RequestParam(required = false) String instrument,
                                                        @RequestParam(required = false) UUID hospitalId,
                                                        @RequestParam(required = false) Integer limit,
                                                        Authentication auth) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        return ResponseEntity.ok(responseService.history(patientId, scope, instrument, limit));
    }

    @PostMapping("/{responseId}/acknowledge")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Acknowledge a safety-item-positive response",
        description = "Stops the escalation chain. Records who acted and what was done.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProResponseDTO> acknowledge(@PathVariable UUID patientId,
                                                      @PathVariable UUID responseId,
                                                      @RequestParam(required = false) UUID hospitalId,
                                                      @RequestBody(required = false) @Valid ProAcknowledgeRequestDTO body,
                                                      Authentication auth) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        String actionTaken = body != null ? body.getActionTaken() : null;
        return ResponseEntity.ok(responseService.acknowledge(patientId, responseId, scope, actionTaken));
    }
}
