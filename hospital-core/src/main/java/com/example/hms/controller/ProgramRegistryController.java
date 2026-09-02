package com.example.hms.controller;

import com.example.hms.enums.CareProgram;
import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.payload.dto.registry.ProgramEnrollmentResponseDTO;
import com.example.hms.service.registry.ProgramEnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The population side of the registries (Tier 2 item 35): one programme,
 * every enrolled patient at the active hospital.
 *
 * <p>Class-level gate on purpose: {@code /programs} has no SecurityConfig
 * matcher, so it rides {@code anyRequest().authenticated()} and a forgotten
 * method annotation would fail OPEN to any authenticated user, including
 * ROLE_PATIENT — on an endpoint that lists patients by disease.
 *
 * <p>Deliberately NOT audited per patient: opening a registry is a
 * population view, the same act as opening the recall worklist, not the act
 * of opening one patient's chart. The chart read is audited where it
 * happens (#536).
 */
@RestController
@RequestMapping(value = "/programs", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR',"
    + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
@Tag(name = "Programme Registries",
    description = "Who is in each disease programme, and who among them is overdue.")
@SecurityRequirement(name = "bearerAuth")
public class ProgramRegistryController {

    private final ProgramEnrollmentService enrollmentService;

    @GetMapping("/{program}/registry")
    @Operation(summary = "The registry for one programme, paged",
        description = "ACTIVE enrolments by default; pass a status for the closed cohorts. "
            + "Overdue-first, so page 0 row 0 is the patient most in need of tracing. "
            + "Paged (size capped server-side) because a cohort is unbounded and one "
            + "request must not serialize every enrolled patient at once.")
    public ResponseEntity<org.springframework.data.domain.Page<ProgramEnrollmentResponseDTO>> registry(
        @PathVariable CareProgram program,
        @RequestParam(required = false) ProgramEnrollmentStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(enrollmentService.registry(program, status, page, size));
    }

    @GetMapping("/{program}/registry/counts")
    @Operation(summary = "Enrolment counts by status for one programme",
        description = "The registry header: how many active, how many lost to follow-up, "
            + "and so on. Absent statuses are simply absent, not zero-filled.")
    public ResponseEntity<Map<ProgramEnrollmentStatus, Long>> counts(
        @PathVariable CareProgram program) {
        return ResponseEntity.ok(enrollmentService.registryCounts(program));
    }
}
