package com.example.hms.controller;

import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.payload.dto.PatientHospitalRegistrationRequestDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import com.example.hms.payload.dto.PatientMultiHospitalSummaryDTO;
import com.example.hms.service.PatientHospitalRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(value = "/registrations", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Patient-Hospital Registration", description = "Manage patient-hospital assignments (staff only)")
@SecurityRequirement(name = "bearerAuth")
public class PatientHospitalRegistrationController {

    private final PatientHospitalRegistrationService registrationService;

    // ---------- Create (Reception / Admin) ----------
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN')")
    @Operation(
        summary = "Assign patient to a hospital (staff only)",
        description = "Receptionist/admin assigns an existing patient to their hospital. " +
            "If hospitalId is omitted, it is taken from the JWT."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Patient assigned to hospital",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = PatientHospitalRegistrationResponseDTO.class))),
        @ApiResponse(responseCode = "404", description = "Patient or hospital not found",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = MessageResponse.class)))
    })
    public ResponseEntity<PatientHospitalRegistrationResponseDTO> registerPatient(
        @Valid @RequestBody PatientHospitalRegistrationRequestDTO dto,
        Locale locale
    ) {
        // If caller didn’t send hospitalId, inject from JWT (reception flow)
        UUID jwtHospitalId = extractHospitalIdFromJwt();
        if (dto.getHospitalId() == null && jwtHospitalId != null) {
            dto.setHospitalId(jwtHospitalId);
        }

       
        PatientHospitalRegistrationResponseDTO created = registrationService.registerPatient(dto);
        return ResponseEntity.created(URI.create("/registrations/" + created.getId())).body(created);
    }

    // ---------- Read by ID ----------
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE', T(com.example.hms.config.SecurityConstants).ROLE_SUPER_ADMIN)")
    @Operation(summary = "Get a patient-hospital registration by ID")
    public ResponseEntity<PatientHospitalRegistrationResponseDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(registrationService.getById(id));
    }

    // ---------- List by patient ----------
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE', T(com.example.hms.config.SecurityConstants).ROLE_SUPER_ADMIN)")
    @Operation(
        summary = "Get hospital registrations for a patient (staff only)",
        description = "Retrieve all hospitals where a patient is registered. " +
            "If page/size are provided, the service may paginate; otherwise returns a simple list."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of patient-hospital registrations",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = PatientHospitalRegistrationResponseDTO.class)))
    })
    public ResponseEntity<List<PatientHospitalRegistrationResponseDTO>> getRegistrations(
        @RequestParam(required = false) UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) Boolean active
    ) {
        if (patientId != null) {
            return ResponseEntity.ok(registrationService.getRegistrationsByPatient(patientId, page, size, active));
        }
        UUID scopeHospitalId = hospitalId != null ? hospitalId : extractHospitalIdFromJwt();
        if (scopeHospitalId != null) {
            return ResponseEntity.ok(registrationService.getRegistrationsByHospital(scopeHospitalId, page, size, active));
        }
        return ResponseEntity.ok(List.of());
    }

    // ---------- Multi-hospital summary ----------
    @GetMapping("/multi-hospital")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE', T(com.example.hms.config.SecurityConstants).ROLE_SUPER_ADMIN)")
    @Operation(
        summary = "List patients registered across multiple hospitals",
        description = "Returns each patient who has an active registration in more than one hospital along with the associated facilities."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Patients with registrations in multiple hospitals",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = PatientMultiHospitalSummaryDTO.class)))
    })
    public ResponseEntity<List<PatientMultiHospitalSummaryDTO>> getPatientsRegisteredInMultipleHospitals() {
        List<PatientMultiHospitalSummaryDTO> summaries = registrationService.getPatientsRegisteredInMultipleHospitals();
        return ResponseEntity.ok(summaries);
    }


    // ---------- Update ----------
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN')")
    @Operation(
        summary = "Update patient-hospital registration (staff only)",
        description = "Update registration details (e.g., active status, metadata)."
    )
    public ResponseEntity<PatientHospitalRegistrationResponseDTO> updateRegistration(
        @PathVariable UUID id,
        @Valid @RequestBody PatientHospitalRegistrationRequestDTO dto
    ) {
        // Optional cross-hospital guard as above (if you add hospitalId in the DTO for update)
        return ResponseEntity.ok(registrationService.updateRegistration(id, dto));
    }

    // ---------- Delete ----------
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN')")
    @Operation(
        summary = "Remove patient from a hospital (staff only)",
        description = "Deregisters a patient from a hospital (removes their assignment)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Patient deregistered from hospital"),
        @ApiResponse(responseCode = "404", description = "Registration not found",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = MessageResponse.class)))
    })
    public ResponseEntity<Void> deregisterPatient(@PathVariable UUID id) {
        registrationService.deregisterPatient(id);
        return ResponseEntity.noContent().build();
    }

    // ===== Helpers =====
    private UUID extractHospitalIdFromJwt() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;

        Object candidate = null;

        Object details = auth.getDetails();
        if (details instanceof Map<?, ?> m) {
            candidate = m.get("hospitalId");
        }
        if (candidate == null && auth.getPrincipal() instanceof Map<?, ?> m) {
            candidate = m.get("hospitalId");
        }

        if (candidate instanceof UUID u) return u;
        if (candidate instanceof String s && !s.isBlank()) {
            try { return UUID.fromString(s); } catch (Exception ignored) {}
        }
        return null;
    }

}
