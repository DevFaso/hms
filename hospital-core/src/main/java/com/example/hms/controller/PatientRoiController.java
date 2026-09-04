package com.example.hms.controller;

import com.example.hms.payload.dto.roi.RoiRequestCreateDTO;
import com.example.hms.payload.dto.roi.RoiRequestResponseDTO;
import com.example.hms.service.roi.RoiRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * One patient's release-of-information requests (Tier 2 item 39b) —
 * intake and chart view. Class-level gate per the fall-open lesson;
 * living under {@code /patients/{patientId}} puts the reads on the
 * patient-access audit interceptor with no further wiring. RECEPTIONIST
 * included: the desk is where paper requests arrive.
 */
@RestController
@RequestMapping(value = "/patients/{patientId}/roi-requests",
    produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR',"
    + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
@Tag(name = "Release of Information",
    description = "Formal requests for a copy of the record, and their outcomes.")
@SecurityRequirement(name = "bearerAuth")
public class PatientRoiController {

    private final RoiRequestService roiService;

    @PostMapping
    @Operation(summary = "Log a release-of-information request for this patient",
        description = "The request row a records desk triages. Purpose and scope are required — "
            + "they are what the decision weighs.")
    public ResponseEntity<RoiRequestResponseDTO> create(
        @PathVariable UUID patientId,
        @Valid @RequestBody RoiRequestCreateDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(roiService.create(patientId, request));
    }

    @GetMapping
    @Operation(summary = "The patient's requests at this hospital, newest first")
    public ResponseEntity<List<RoiRequestResponseDTO>> list(@PathVariable UUID patientId) {
        return ResponseEntity.ok(roiService.patientRequests(patientId));
    }
}
