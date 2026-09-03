package com.example.hms.controller;

import com.example.hms.payload.dto.registry.ProgramEnrollmentRequestDTO;
import com.example.hms.payload.dto.registry.ProgramEnrollmentResponseDTO;
import com.example.hms.payload.dto.registry.ProgramStatusUpdateDTO;
import com.example.hms.payload.dto.registry.ProgramVisitDTO;
import com.example.hms.service.registry.ProgramEnrollmentService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * One patient's programme enrolments (Tier 2 item 35).
 *
 * <p>Class-level gate on purpose. Only the GET rides a SecurityConfig
 * matcher (the broad {@code /patients/**} read set); the writes fall through
 * to {@code anyRequest().authenticated()}, where a forgotten method
 * annotation fails OPEN to any authenticated user, including ROLE_PATIENT.
 * The role set is the clinical write set the postpartum module uses —
 * MIDWIFE included because ANC is one of the six programmes.
 *
 * <p>Path deliberately under {@code /patients/{patientId}} so the
 * patient-access audit interceptor (#536) records chart reads here with no
 * further wiring.
 */
@RestController
@RequestMapping(value = "/patients/{patientId}/programs",
    produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR',"
    + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
@Tag(name = "Programme Enrolments",
    description = "Disease-programme registry enrolments for one patient.")
@SecurityRequirement(name = "bearerAuth")
public class ProgramEnrollmentController {

    private final ProgramEnrollmentService enrollmentService;

    @PostMapping
    @Operation(summary = "Enrol the patient in a care programme",
        description = "One ACTIVE enrolment per programme per hospital; a second is refused, "
            + "not merged. The visit cadence comes from the clinician — the server has no "
            + "per-programme default on purpose.")
    public ResponseEntity<ProgramEnrollmentResponseDTO> enroll(
        @PathVariable UUID patientId,
        @Valid @RequestBody ProgramEnrollmentRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(enrollmentService.enroll(patientId, request));
    }

    @GetMapping
    @Operation(summary = "The patient's enrolments, every programme and state, newest first")
    public ResponseEntity<List<ProgramEnrollmentResponseDTO>> list(@PathVariable UUID patientId) {
        return ResponseEntity.ok(enrollmentService.patientEnrollments(patientId));
    }

    @PutMapping("/{enrollmentId}/status")
    @Operation(summary = "Move an enrolment between ACTIVE and a closed state",
        description = "Closing needs a reason — it is the outcome the programme reports. "
            + "Re-opening refuses one, and refuses to create a second ACTIVE enrolment.")
    public ResponseEntity<ProgramEnrollmentResponseDTO> updateStatus(
        @PathVariable UUID patientId,
        @PathVariable UUID enrollmentId,
        @Valid @RequestBody ProgramStatusUpdateDTO request) {
        return ResponseEntity.ok(enrollmentService.updateStatus(patientId, enrollmentId, request));
    }

    @PostMapping("/{enrollmentId}/visit")
    @Operation(summary = "Record that a programme visit happened",
        description = "Advances the next expected visit by the enrolment's cadence. Records a "
            + "visit that happened — it books nothing.")
    public ResponseEntity<ProgramEnrollmentResponseDTO> recordVisit(
        @PathVariable UUID patientId,
        @PathVariable UUID enrollmentId,
        @RequestBody(required = false) @Valid ProgramVisitDTO request) {
        return ResponseEntity.ok(enrollmentService.recordVisit(patientId, enrollmentId, request));
    }
}
