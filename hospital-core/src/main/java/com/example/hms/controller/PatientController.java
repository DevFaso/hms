package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.DoctorPatientRecordDTO;
import com.example.hms.payload.dto.DoctorPatientRecordRequestDTO;
import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.payload.dto.DoctorPatientChartUpdateRequestDTO;
import com.example.hms.payload.dto.PatientAllergyResponseDTO;
import com.example.hms.payload.dto.PatientAllergyDeactivateRequestDTO;
import com.example.hms.payload.dto.PatientAllergyRequestDTO;
import com.example.hms.payload.dto.PatientChartUpdateResponseDTO;
import com.example.hms.payload.dto.PatientDiagnosisDeleteRequestDTO;
import com.example.hms.payload.dto.PatientDiagnosisRequestDTO;
import com.example.hms.payload.dto.PatientDiagnosisUpdateRequestDTO;
import com.example.hms.payload.dto.PatientProblemResponseDTO;
import com.example.hms.payload.dto.PatientProfileUpdateRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PatientSearchCriteria;
import com.example.hms.payload.dto.PatientTimelineAccessRequestDTO;
import com.example.hms.payload.dto.PatientTimelineResponseDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.NurseDashboardService;
import com.example.hms.service.PatientService;
import com.example.hms.service.PatientChartUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/patients")
@RequiredArgsConstructor
@Tag(name = "Patient Management", description = "CRUD operations for patients (staff only)")
public class PatientController {

    private static final String ROLE_RECEPTIONIST = "ROLE_RECEPTIONIST";
    private static final String MSG_DOCTOR_CONTEXT_REQUIRED = "Unable to resolve authenticated doctor context.";
    private static final List<String> CLINICAL_CHART_UPDATE_ROLES = List.of(
        "ROLE_DOCTOR",
        "ROLE_NURSE",
        "ROLE_MIDWIFE",
        "ROLE_HOSPITAL_ADMIN"
    );

    private final PatientService patientService;
    private final NurseDashboardService nurseDashboardService;
    private final PatientChartUpdateService patientChartUpdateService;
    private final MessageSource messageSource;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    // ----------------------------------------------------------
    // List
    // ----------------------------------------------------------
    @Operation(
        summary = "Get all patients (staff only)",
        description = "Retrieve patients. Receptionists are automatically scoped to their hospital from JWT.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "200", description = "List of patients retrieved",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PatientResponseDTO.class)))
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_SUPER_ADMIN')")
    public ResponseEntity<List<PatientResponseDTO>> getAllPatients(
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(name = "assignedTo", required = false) String assignedTo,
        @RequestParam(name = "inhouse", required = false) String inhouse,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        requireAuth(auth);
        Locale locale = parseLocale(lang);
        if (assignedTo != null && assignedTo.equalsIgnoreCase("me")) {
            UUID nurseId = resolveUserId(auth)
                .orElseThrow(() -> new BusinessException("Unable to resolve authenticated user identifier for nurse dashboard filtering."));
            UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, false);
            if (resolvedHospitalId == null) {
                throw new BusinessException("Hospital context required when filtering by assigned nurse.");
            }
            LocalDate inhouseDate = resolveInhouseDate(inhouse);
            List<PatientResponseDTO> patients = nurseDashboardService
                .getPatientsForNurse(nurseId, resolvedHospitalId, inhouseDate);
            return ResponseEntity.ok(patients);
        }
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, true);
        return ResponseEntity.ok(patientService.getAllPatients(resolvedHospitalId, locale));
    }

    // ----------------------------------------------------------
    // Create (Receptionist/Admin)
    // ----------------------------------------------------------
    @Operation(
    summary = "Register new patient (staff only)",
    description = "Receptionists and nurses can register a new patient. Hospital is taken from JWT for receptionists.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "201", description = "Patient registered successfully",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PatientResponseDTO.class)))
    @ApiResponse(responseCode = "409", description = "Username or email already exists",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = MessageResponse.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
    public ResponseEntity<PatientResponseDTO> createPatientByReceptionist(
        @Valid @RequestBody PatientRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        Locale locale = parseLocale(lang);
        UUID resolvedHospitalId = resolveHospitalScope(auth, dto.getHospitalId(), true);
        dto.setHospitalId(resolvedHospitalId); // enforce scoping
        PatientResponseDTO created = patientService.createPatientByStaff(dto, locale);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    // ----------------------------------------------------------
    // Read by ID
    // ----------------------------------------------------------
    @Operation(
        summary = "Get patient by ID (staff only)",
        description = "Retrieve a specific patient. Receptionists are scoped to their hospital.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "200", description = "Patient found",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PatientResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Patient not found",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = MessageResponse.class)))
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PatientResponseDTO> getPatientById(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID hospitalId,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        Locale locale = parseLocale(lang);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, false);
        return ResponseEntity.ok(patientService.getPatientById(id, resolvedHospitalId, locale));
    }

    // ----------------------------------------------------------
    // Update
    // ----------------------------------------------------------
    @Operation(
        summary = "Update patient profile (staff only)",
        description = "Update a patient's profile. Receptionists are scoped to their hospital.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "200", description = "Patient updated",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PatientResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Patient not found",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = MessageResponse.class)))
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PatientResponseDTO> updatePatient(
        @PathVariable UUID id,
        @Valid @RequestBody PatientRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        Locale locale = parseLocale(lang);
        UUID resolvedHospitalId = resolveHospitalScope(auth, dto.getHospitalId(), false);
        dto.setHospitalId(resolvedHospitalId);
        return ResponseEntity.ok(patientService.updatePatient(id, dto, locale));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PatientResponseDTO> patchPatient(
        @PathVariable UUID id,
        @Valid @RequestBody PatientProfileUpdateRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(patientService.patchPatient(id, dto, locale));
    }

    // ----------------------------------------------------------
    // Delete
    // ----------------------------------------------------------
    @Operation(
        summary = "Delete patient profile (admin only)",
        description = "Deletes a patient profile and corresponding user record.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "200", description = "Patient deleted",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = MessageResponse.class)))
    @ApiResponse(responseCode = "404", description = "Patient not found",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = MessageResponse.class)))
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<MessageResponse> deletePatient(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        patientService.deletePatient(id, locale);
        String message = messageSource.getMessage("patient.deleted", null, "Patient deleted successfully", locale);
        return ResponseEntity.ok(new MessageResponse(message));
    }

    // ----------------------------------------------------------
    // Search
    // ----------------------------------------------------------
    @Operation(
        summary = "Search patients (staff only)",
        description = "Search by MRN, name+DOB, phone, email. Receptionists scoped to their hospital.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "200", description = "Patients found",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PatientResponseDTO.class)))
    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<List<PatientResponseDTO>> searchPatients(
        @RequestParam(required = false) String mrn,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) String dob,
        @RequestParam(required = false) String phone,
        @RequestParam(required = false) String email,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "true") boolean active,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        Locale locale = parseLocale(lang);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, false);
        PatientSearchCriteria criteria = PatientSearchCriteria.builder()
            .mrn(mrn)
            .name(name)
            .dateOfBirth(dob)
            .phone(phone)
            .email(email)
            .hospitalId(resolvedHospitalId)
            .active(active)
            .build();
        return ResponseEntity.ok(patientService.searchPatients(criteria, page, size, locale));
    }

    // ----------------------------------------------------------
    // Lookup (flex identifiers)
    // ----------------------------------------------------------
    @Operation(
        summary = "Lookup patient(s) by flexible identifier (receptionist & staff)",
        description = "Provide one of: email, phone, username, MRN (param 'mrn'). 'mri' is accepted for backward compatibility.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "200", description = "Lookup completed",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PatientResponseDTO.class)))
    @GetMapping("/lookup")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_SUPER_ADMIN')")
    public ResponseEntity<List<PatientResponseDTO>> lookupPatients(
        @RequestParam(required = false) String identifier,
        @RequestParam(required = false) String email,
        @RequestParam(required = false) String phone,
        @RequestParam(required = false) String username,
        @RequestParam(name = "mrn", required = false) String mrn,
        @RequestParam(name = "mri", required = false) String legacyMri, // legacy alias
        @RequestParam(required = false) UUID hospitalId,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        requireAuth(auth);
        Locale locale = parseLocale(lang);
        String effectiveMrn = (mrn != null && !mrn.isBlank()) ? mrn : legacyMri;
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, false);
        List<PatientResponseDTO> list =
            patientService.lookupPatients(identifier, email, phone, username, effectiveMrn, resolvedHospitalId, locale);
        return ResponseEntity.ok(list);
    }

    @Operation(
        summary = "Doctor consolidated record view",
        description = "Provides a comprehensive patient record snapshot with sensitive data gating and audit logging.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{id}/doctor-record")
    @PreAuthorize("hasAuthority('ROLE_DOCTOR')")
    public ResponseEntity<DoctorPatientRecordDTO> getDoctorRecord(
        @PathVariable UUID id,
        @Valid @RequestBody DoctorPatientRecordRequestDTO request,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID userId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = resolveHospitalScope(auth, request.getHospitalId(), false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Doctor record requests require an explicit hospital context.");
        }
        UserRoleHospitalAssignment assignment = resolveDoctorAssignment(userId, resolvedHospitalId);
        DoctorPatientRecordDTO response = patientService
            .getDoctorRecord(id, resolvedHospitalId, userId, assignment, request);
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Doctor timeline view",
        description = "Provides a longitudinal patient timeline with sensitive data gating and audit logging.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{id}/doctor-timeline")
    @PreAuthorize("hasAuthority('ROLE_DOCTOR')")
    public ResponseEntity<PatientTimelineResponseDTO> getDoctorTimeline(
        @PathVariable UUID id,
        @Valid @RequestBody PatientTimelineAccessRequestDTO request,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID userId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = resolveHospitalScope(auth, request.getHospitalId(), false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Doctor timeline requests require an explicit hospital context.");
        }
        UserRoleHospitalAssignment assignment = resolveDoctorAssignment(userId, resolvedHospitalId);
        PatientTimelineResponseDTO response = patientService
            .getDoctorTimeline(id, resolvedHospitalId, userId, assignment, request);
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "List structured allergy entries for prescribing",
        description = "Provides an audit-friendly list of allergy entries scoped to the clinician's hospital.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{id}/allergies")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_PHARMACIST')")
    public ResponseEntity<List<PatientAllergyResponseDTO>> getPatientAllergies(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID userId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("Unable to resolve authenticated clinician context."));
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Hospital context is required to view allergy history.");
        }
        List<PatientAllergyResponseDTO> allergies = patientService
            .getPatientAllergies(id, resolvedHospitalId, userId);
        return ResponseEntity.ok(allergies);
    }

    @Operation(
        summary = "Create allergy entry",
        description = "Allows clinicians to capture a structured allergy entry within their hospital context.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{id}/allergies")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_PHARMACIST')")
    public ResponseEntity<PatientAllergyResponseDTO> createPatientAllergy(
        @PathVariable UUID id,
        @Valid @RequestBody PatientAllergyRequestDTO request,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID requesterUserId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = resolveHospitalScope(auth, request.getHospitalId(), false);
        UUID effectiveHospitalId = resolvedHospitalId != null ? resolvedHospitalId : request.getHospitalId();
        if (effectiveHospitalId == null) {
            throw new BusinessException("Hospital context is required to add allergies.");
        }
        request.setHospitalId(effectiveHospitalId);
        PatientAllergyResponseDTO created = patientService
            .createPatientAllergy(id, effectiveHospitalId, requesterUserId, request);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @Operation(
        summary = "Update allergy entry",
        description = "Allows clinicians to revise allergy metadata, severity, or verification status.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PutMapping("/{id}/allergies/{allergyId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_PHARMACIST')")
    public ResponseEntity<PatientAllergyResponseDTO> updatePatientAllergy(
        @PathVariable UUID id,
        @PathVariable UUID allergyId,
        @Valid @RequestBody PatientAllergyRequestDTO request,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID requesterUserId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = resolveHospitalScope(auth, request.getHospitalId(), false);
        UUID effectiveHospitalId = resolvedHospitalId != null ? resolvedHospitalId : request.getHospitalId();
        if (effectiveHospitalId == null) {
            throw new BusinessException("Hospital context is required to update allergies.");
        }
        request.setHospitalId(effectiveHospitalId);
        PatientAllergyResponseDTO updated = patientService
            .updatePatientAllergy(id, effectiveHospitalId, allergyId, requesterUserId, request);
        return ResponseEntity.ok(updated);
    }

    @Operation(
        summary = "Deactivate allergy entry",
        description = "Marks an allergy as inactive while providing an audit reason.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @DeleteMapping("/{id}/allergies/{allergyId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_PHARMACIST')")
    public ResponseEntity<MessageResponse> deactivatePatientAllergy(
        @PathVariable UUID id,
        @PathVariable UUID allergyId,
        @RequestParam(required = false) UUID hospitalId,
        @Valid @RequestBody PatientAllergyDeactivateRequestDTO request,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID requesterUserId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Hospital context is required to deactivate allergies.");
        }
        patientService.deactivatePatientAllergy(id, resolvedHospitalId, allergyId, requesterUserId, request.getReason());
        return ResponseEntity.ok(new MessageResponse("Allergy entry deactivated."));
    }

    // ----------------------------------------------------------
    // Diagnoses Management
    // ----------------------------------------------------------
    @Operation(
        summary = "List patient diagnoses",
        description = "Returns active diagnoses by default; includeHistorical=true returns resolved/inactive entries as well.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{id}/diagnoses")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_HOSPITAL_ADMIN','ROLE_MIDWIFE')")
    public ResponseEntity<List<PatientProblemResponseDTO>> listPatientDiagnoses(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(name = "includeHistorical", defaultValue = "false") boolean includeHistorical,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Hospital context is required to view diagnoses.");
        }
        List<PatientProblemResponseDTO> diagnoses = patientService
            .listPatientDiagnoses(id, resolvedHospitalId, includeHistorical);
        return ResponseEntity.ok(diagnoses);
    }

    @Operation(
        summary = "Create patient diagnosis",
        description = "Allows doctors to capture a new diagnosis entry with supporting evidence and coding.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{id}/diagnoses")
    @PreAuthorize("hasAuthority('ROLE_DOCTOR')")
    public ResponseEntity<PatientProblemResponseDTO> createPatientDiagnosis(
        @PathVariable UUID id,
        @Valid @RequestBody PatientDiagnosisRequestDTO request,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID requesterUserId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = resolveHospitalScope(auth, request.getHospitalId(), false);
        UUID effectiveHospitalId = resolvedHospitalId != null ? resolvedHospitalId : request.getHospitalId();
        if (effectiveHospitalId == null) {
            throw new BusinessException("Hospital context is required to add diagnoses.");
        }
        request.setHospitalId(effectiveHospitalId);
        PatientProblemResponseDTO created = patientService
            .createPatientDiagnosis(id, effectiveHospitalId, requesterUserId, request);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @Operation(
        summary = "Update patient diagnosis",
        description = "Allows doctors to revise existing diagnoses, capturing reasons for status changes.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PutMapping("/{id}/diagnoses/{diagnosisId}")
    @PreAuthorize("hasAuthority('ROLE_DOCTOR')")
    public ResponseEntity<PatientProblemResponseDTO> updatePatientDiagnosis(
        @PathVariable UUID id,
        @PathVariable UUID diagnosisId,
        @Valid @RequestBody PatientDiagnosisUpdateRequestDTO request,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID requesterUserId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = resolveHospitalScope(auth, request.getHospitalId(), false);
        UUID effectiveHospitalId = resolvedHospitalId != null ? resolvedHospitalId : request.getHospitalId();
        if (effectiveHospitalId == null) {
            throw new BusinessException("Hospital context is required to update diagnoses.");
        }
        request.setHospitalId(effectiveHospitalId);
        PatientProblemResponseDTO updated = patientService
            .updatePatientDiagnosis(id, effectiveHospitalId, diagnosisId, requesterUserId, request);
        return ResponseEntity.ok(updated);
    }

    @Operation(
        summary = "Remove patient diagnosis",
        description = "Marks a diagnosis as inactive with an audit justification.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @DeleteMapping("/{id}/diagnoses/{diagnosisId}")
    @PreAuthorize("hasAuthority('ROLE_DOCTOR')")
    public ResponseEntity<MessageResponse> deletePatientDiagnosis(
        @PathVariable UUID id,
        @PathVariable UUID diagnosisId,
        @RequestParam(required = false) UUID hospitalId,
        @Valid @RequestBody PatientDiagnosisDeleteRequestDTO request,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID requesterUserId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Hospital context is required to remove diagnoses.");
        }
        patientService.deletePatientDiagnosis(id, resolvedHospitalId, diagnosisId, requesterUserId, request.getReason());
        return ResponseEntity.ok(new MessageResponse("Diagnosis removed successfully."));
    }

    @Operation(
        summary = "List patient chart updates",
        description = "Paginated history of clinician-authored chart updates for the specified patient.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{id}/chart-updates")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
    public ResponseEntity<Page<PatientChartUpdateResponseDTO>> listPatientChartUpdates(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID hospitalId,
        @PageableDefault(size = 20, sort = "versionNumber", direction = Sort.Direction.DESC) Pageable pageable,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID userId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("Unable to resolve authenticated user context."));
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Hospital context is required to view chart updates.");
        }
        resolveClinicalAssignment(userId, resolvedHospitalId);
        Page<PatientChartUpdateResponseDTO> response = patientChartUpdateService
            .listPatientChartUpdates(id, resolvedHospitalId, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Get patient chart update",
        description = "Fetch a single chart update by its identifier.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{patientId}/chart-updates/{updateId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
    public ResponseEntity<PatientChartUpdateResponseDTO> getPatientChartUpdate(
        @PathVariable UUID patientId,
        @PathVariable UUID updateId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID userId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("Unable to resolve authenticated user context."));
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("Hospital context is required to view chart updates.");
        }
        resolveClinicalAssignment(userId, resolvedHospitalId);
        PatientChartUpdateResponseDTO response = patientChartUpdateService
            .getPatientChartUpdate(patientId, resolvedHospitalId, updateId);
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Create patient chart update",
        description = "Allows clinicians to capture structured patient chart updates.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{patientId}/chart-updates")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<PatientChartUpdateResponseDTO> createPatientChartUpdate(
        @PathVariable UUID patientId,
        @Valid @RequestBody DoctorPatientChartUpdateRequestDTO request,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID userId = resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("Unable to resolve authenticated clinician context."));
        UUID resolvedHospitalId = resolveHospitalScope(auth, request.getHospitalId(), false);
        if (resolvedHospitalId == null && request.getHospitalId() == null) {
            throw new BusinessException("Hospital context is required to create chart updates.");
        }
        UUID effectiveHospital = resolvedHospitalId != null ? resolvedHospitalId : request.getHospitalId();
        UserRoleHospitalAssignment assignment = resolveClinicalAssignment(userId, effectiveHospital);
        PatientChartUpdateResponseDTO response = patientChartUpdateService
            .createPatientChartUpdate(patientId, effectiveHospital, userId, assignment, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    // ==========================================================
    // Helpers
    // ==========================================================
    private void requireAuth(Authentication auth) {
        if (auth == null) throw new BusinessException("Authentication required.");
    }

    private Optional<UUID> resolveUserId(Authentication auth) {
        if (auth == null) {
            return Optional.empty();
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof CustomUserDetails cud) {
            return Optional.ofNullable(cud.getUserId());
        }
        if (auth instanceof JwtAuthenticationToken jat) {
            Jwt jwt = jat.getToken();
            for (String claim : List.of("uid", "userId", "id", "sub")) {
                String raw = jwt.getClaimAsString(claim);
                if (raw != null && !raw.isBlank()) {
                    try {
                        return Optional.of(UUID.fromString(raw));
                    } catch (IllegalArgumentException ignored) {
                        // try the next claim key
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Locale parseLocale(String header) {
        if (header == null || header.isBlank()) return Locale.getDefault();
        String first = header.split(",")[0].trim().replace('_','-');
        if (!isValidLocaleToken(first)) {
            return Locale.getDefault();
        }
        try {
            Locale.Builder b = new Locale.Builder();
            String[] parts = first.split("-");
            b.setLanguage(parts[0]);
            if (parts.length >= 2) {
                b.setRegion(parts[1]);
            }
            if (parts.length >= 3) {
                b.setVariant(parts[2]);
            }
            return b.build();
        } catch (RuntimeException e) {
            return Locale.getDefault();
        }
    }

    private boolean isValidLocaleToken(String value) {
        if (value == null) {
            return false;
        }
        String[] segments = value.split("-");
        if (segments.length == 0 || segments.length > 3) {
            return false;
        }
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment == null) {
                return false;
            }
            String trimmed = segment.trim();
            if (trimmed.length() < 2 || trimmed.length() > 8) {
                return false;
            }
            if (!trimmed.chars().allMatch(Character::isLetterOrDigit)) {
                return false;
            }
            if (i == 0 && !trimmed.chars().allMatch(Character::isLetter)) {
                return false;
            }
        }
        return true;
    }
    private LocalDate resolveInhouseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if ("today".equalsIgnoreCase(raw.trim())) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException("Invalid inhouse date; expected ISO format yyyy-MM-dd or 'today'.");
        }
    }

    /**
     * Resolve hospital scoping for the current request.
     * Rules:
     * - RECEPTIONIST: prefer hospitalId from JWT; else use provided hospitalId; else error if required.
     * - HOSPITAL_ADMIN / SUPER_ADMIN: use provided hospitalId if any; else JWT; else null (global).
     * - Others (DOCTOR/NURSE): use provided hospitalId if any; else JWT; else null.
     */
    private UUID resolveHospitalScope(Authentication auth, UUID requestedHospitalId, boolean requiredForReceptionist) {
        UUID jwtHospitalId = extractHospitalIdFromJwt(auth);

        if (hasAuthority(auth, "ROLE_SUPER_ADMIN")) {
            return preferHospital(requestedHospitalId, jwtHospitalId)
                .or(() -> fallbackHospitalFromAssignments(auth))
                .orElse(null);
        }

        if (hasAuthority(auth, ROLE_RECEPTIONIST)) {
            return resolveReceptionistScope(auth, requestedHospitalId, jwtHospitalId, requiredForReceptionist);
        }

        if (hasAuthority(auth, "ROLE_HOSPITAL_ADMIN")) {
            return preferHospital(requestedHospitalId, jwtHospitalId)
                .or(() -> fallbackHospitalFromAssignments(auth))
                .orElse(null);
        }

        return preferHospital(requestedHospitalId, jwtHospitalId)
            .or(() -> fallbackHospitalFromAssignments(auth))
            .orElse(null);
    }

    private UUID resolveReceptionistScope(Authentication auth, UUID requestedHospitalId, UUID jwtHospitalId, boolean requiredForReceptionist) {
        UUID enforcedFromJwt = jwtHospitalId;
        if (enforcedFromJwt != null) {
            if (requestedHospitalId != null && !requestedHospitalId.equals(enforcedFromJwt)
                && isReceptionistAssignedToHospital(auth, requestedHospitalId)) {
                return requestedHospitalId;
            }
            return enforcedFromJwt;
        }

        if (requestedHospitalId != null) {
            if (isReceptionistAssignedToHospital(auth, requestedHospitalId)) {
                return requestedHospitalId;
            }
            throw new BusinessException("Receptionist is not assigned to the requested hospital.");
        }

        Optional<UUID> assignmentHospital = receptionistAssignmentHospital(auth);
        if (assignmentHospital.isPresent()) {
            return assignmentHospital.get();
        }

        if (requiredForReceptionist) {
            throw new BusinessException("Receptionist must be affiliated with a hospital (provide hospitalId in token or request).");
        }
        return null;
    }

    private Optional<UUID> receptionistAssignmentHospital(Authentication auth) {
        return resolveUserId(auth)
            .flatMap(userId -> assignmentRepository
                .findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, ROLE_RECEPTIONIST))
            .map(UserRoleHospitalAssignment::getHospital)
            .filter(Objects::nonNull)
            .map(Hospital::getId);
    }

    private UserRoleHospitalAssignment resolveDoctorAssignment(UUID userId, UUID hospitalId) {
        return assignmentRepository
            .findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, hospitalId, "ROLE_DOCTOR")
            .orElseThrow(() -> new BusinessException("Doctor assignment not found for requested hospital."));
    }

    private UserRoleHospitalAssignment resolveClinicalAssignment(UUID userId, UUID hospitalId) {
        if (userId == null || hospitalId == null) {
            throw new BusinessException("Hospital context is required for clinician authorization.");
        }
        for (String role : CLINICAL_CHART_UPDATE_ROLES) {
            Optional<UserRoleHospitalAssignment> assignment = assignmentRepository
                .findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, hospitalId, role);
            if (assignment.isPresent()) {
                return assignment.get();
            }
        }
        throw new BusinessException("No active clinician assignment found for requested hospital context.");
    }

    private boolean isReceptionistAssignedToHospital(Authentication auth, UUID hospitalId) {
        if (hospitalId == null) {
            return false;
        }
        return resolveUserId(auth)
            .map(userId -> assignmentRepository
                .existsByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, hospitalId, ROLE_RECEPTIONIST))
            .orElse(false);
    }

    private Optional<UUID> preferHospital(UUID requestedHospitalId, UUID jwtHospitalId) {
        if (requestedHospitalId != null) {
            return Optional.of(requestedHospitalId);
        }
        if (jwtHospitalId != null) {
            return Optional.of(jwtHospitalId);
        }
        return Optional.empty();
    }

    private Optional<UUID> fallbackHospitalFromAssignments(Authentication auth) {
        return resolveUserId(auth)
            .flatMap(assignmentRepository::findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc)
            .map(UserRoleHospitalAssignment::getHospital)
            .filter(Objects::nonNull)
            .map(Hospital::getId);
    }

    private boolean hasAuthority(Authentication auth, String authority) {
        if (auth == null || auth.getAuthorities() == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> authority.equalsIgnoreCase(a.getAuthority()));
    }

    private UUID extractHospitalIdFromJwt(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) {
            Jwt jwt = jat.getToken();
            // flat claim
            String s = jwt.getClaimAsString("hospitalId");
            if (s != null && !s.isBlank()) {
                try { return UUID.fromString(s); } catch (RuntimeException ignored) {
                    // ignore invalid UUID formats and continue fallback logic
                }
            }
            // nested/custom if needed
            Object raw = jwt.getClaims().get("hospitalId");
            if (raw instanceof UUID u) return u;
            if (raw instanceof String str && !str.isBlank()) {
                try { return UUID.fromString(str); } catch (RuntimeException ignored) {
                    // ignore invalid UUID formats and continue fallback logic
                }
            }
        }
        return null;
    }
}
