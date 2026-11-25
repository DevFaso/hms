package com.example.hms.controller;

import com.example.hms.payload.dto.identifiersearch.PatientLookupRequestDTO;
import com.example.hms.payload.dto.identifiersearch.PatientSearchRequestDTO;
import com.example.hms.payload.dto.identifiersearch.RegistrationMatchRequestDTO;
import com.example.hms.payload.dto.identifiersearch.SearchTermRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
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
import com.example.hms.payload.dto.ChartRestrictionRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.RegistrationMatchDTO;
import com.example.hms.payload.dto.PatientSearchCriteria;
import com.example.hms.payload.dto.PatientTimelineAccessRequestDTO;
import com.example.hms.payload.dto.PatientTimelineResponseDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
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

import static com.example.hms.config.SecurityConstants.CONSULTING_CLINICIANS_AUTHORITIES;
import org.springframework.security.core.Authentication;
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
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/patients")
@RequiredArgsConstructor
@Tag(name = "Patient Management", description = "CRUD operations for patients (staff only)")
public class PatientController {

    /** How many matches the ?search= form of GET /patients returns: a typeahead, not a list. */
    private static final int LIST_SEARCH_LIMIT = 20;
    /** QA D504 — tells the chart that a sharing opt-out narrowed the list. */
    static final String RECORDS_WITHHELD_HEADER = "X-Records-Withheld";
    static final String RECORDS_WITHHELD_OTHER_HOSPITALS = "OTHER_HOSPITALS";

    private static final String ROLE_RECEPTIONIST = "ROLE_RECEPTIONIST";
    private static final String MSG_DOCTOR_CONTEXT_REQUIRED = "Unable to resolve authenticated doctor context.";
    private static final List<String> CLINICAL_CHART_UPDATE_ROLES = List.of(
        "ROLE_DOCTOR",
        "ROLE_NURSE",
        "ROLE_MIDWIFE",
        "ROLE_HOSPITAL_ADMIN"
    );

    /**
     * Chart read (list + detail) — the /patients page. One shared list because
     * list and detail had drifted into two hand-maintained copies of the same
     * set. Narrower than the SecurityConfig GET /patients matcher on purpose:
     * the matcher also covers the picker endpoints below, whose callers have no
     * business paging the whole chart.
     *
     * <p>The consulting clinicians were admitted by role audit D7, together
     * with the other four layers the chart page needs — see
     * {@link com.example.hms.config.SecurityConstants#CONSULTING_CLINICIANS_AUTHORITIES}.
     */
    private static final String PATIENT_READ_ROLES = "hasAnyAuthority("
        + "'ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE',"
        + "'ROLE_LAB_SCIENTIST','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER','ROLE_LAB_DIRECTOR','ROLE_QUALITY_MANAGER',"
        + CONSULTING_CLINICIANS_AUTHORITIES + ","
        // E9 #69: the pharmacist reads the chart (diagnoses, vitals, results) to
        // verify a prescription; the picker, the medication tab and chart review
        // already assumed it while this list refused the demographics page.
        + "'ROLE_PHARMACIST',"
        + "'ROLE_SUPER_ADMIN')";

    /**
     * Shared patient picker (/search + /lookup) — every role whose pages embed
     * {@code <app-patient-picker>} needs it, which is why LAB_SCIENTIST and
     * PHARMACIST are here (/medication-history). RADIOLOGIST (/imaging) and
     * PHYSIOTHERAPIST (/treatment-plans, admitted by audit decision C4) reach
     * those pages through their route guards but the picker rejected them, so
     * the search box was dead — 2026-08-23 role audit, D4.
     */
    private static final String PATIENT_PICKER_ROLES = "hasAnyAuthority("
        + "'ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE',"
        + "'ROLE_LAB_SCIENTIST','ROLE_PHARMACIST','ROLE_RADIOLOGIST','ROLE_PHYSIOTHERAPIST','ROLE_SUPER_ADMIN',"
        // QA D92: the invoice form's patient picker.
        + "'ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT')";

    /**
     * QA D1224 (round 24), as in real hospitals: the clinicians of the
     * patient's hospital who chart - doctor, nurse, midwife, and the pharmacist
     * for allergies - record, revise and deactivate an allergy (deactivation
     * with a reason, audited; never a hard delete).
     */
    private static final String ALLERGY_WRITE_ROLES =
        "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','NURSE','MIDWIFE','PHARMACIST')";

    /**
     * QA D1224 (round 24): a diagnosis entered in error is removed (set
     * INACTIVE with a reason, audited in its history) by a clinician of the
     * patient's hospital - doctor, nurse or midwife. Recording or recoding a
     * diagnosis stays the doctor's.
     */
    private static final String DIAGNOSIS_REMOVE_ROLES =
        "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','NURSE','MIDWIFE')";

    /**
     * E9 #69 — allergies are readable by every clinical role. A radiologist
     * planning contrast, an anaesthetist planning induction and a
     * physiotherapist planning a session each need the allergy list; before
     * this only the chart-owning roles and the pharmacist could read it.
     * Writes stay with the roles that record allergies.
     */
    private static final String ALLERGY_READ_ROLES = "hasAnyAuthority("
        + "'ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST',"
        + CONSULTING_CLINICIANS_AUTHORITIES + ")";

    private final PatientService patientService;
    private final NurseDashboardService nurseDashboardService;
    private final PatientChartUpdateService patientChartUpdateService;
    private final MessageSource messageSource;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final ControllerAuthUtils authUtils;

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
    @PreAuthorize(PATIENT_READ_ROLES)
    public ResponseEntity<List<PatientResponseDTO>> getAllPatients(
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(name = "assignedTo", required = false) String assignedTo,
        @RequestParam(name = "inhouse", required = false) String inhouse,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        Locale locale = parseLocale(lang);
        if (assignedTo != null && assignedTo.equalsIgnoreCase("me")) {
            UUID nurseId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException("request.userContext.unavailable"));
            UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
            if (resolvedHospitalId == null) {
                throw new BusinessException("request.hospitalContext.required");
            }
            LocalDate inhouseDate = resolveInhouseDate(inhouse);
            List<PatientResponseDTO> patients = nurseDashboardService
                .getPatientsForNurse(nurseId, resolvedHospitalId, inhouseDate);
            return ResponseEntity.ok(patients);
        }
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, true);
        return ResponseEntity.ok(patientService.getAllPatients(resolvedHospitalId, locale));
    }

    /**
     * The list narrowed to a typed term: the shared patient typeahead (admission, encounter,
     * consultation, lab, appointment and walk-in pickers). The term travels in the body, never in
     * the URL (proxy and access logs, browser history, front-end tracing); the hospital scope stays
     * in the query. Same roles and scope as the list.
     */
    @Operation(
        summary = "Search the patient list by a typed term (staff only)",
        description = "The term (a name, an MRN, a phone number, a birth date or an e-mail) is in the body.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @com.example.hms.security.ReadsOnly("the patient typeahead, under POST so the typed term stays out of the URL")
    @PostMapping("/term-search")
    @PreAuthorize(PATIENT_READ_ROLES)
    public ResponseEntity<List<PatientResponseDTO>> termSearchPatients(
        @jakarta.validation.Valid @RequestBody SearchTermRequestDTO body,
        @RequestParam(required = false) UUID hospitalId,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        Locale locale = parseLocale(lang);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, true);
        // The admission, encounter, consultation, lab and appointment
        // typeaheads search the list (QA D79). The term used to be dropped, so the
        // first patients of the hospital were offered whatever was typed and
        // the wrong patient was admitted. Same matching as /patients/search
        // (name or MRN), and, like the unfiltered list, inactive records too.
        // QA D1019: a typed phone number ("65112233", "65 11 22 33",
        // "+226 65 11 22 33") is searched as a number, folded like the
        // duplicate check; it used to be matched against names only, so
        // the appointment and walk-in pickers never found a patient by phone.
        // QA D1274: the service reads the typed term (a phone number, a
        // birth date, an e-mail or the start of one, else a name or MRN).
        PatientSearchCriteria criteria = PatientSearchCriteria.builder()
            .name(body.term())
            .hospitalId(resolvedHospitalId)
            .includeInactive(true)
            .build();
        return ResponseEntity.ok(patientService.searchPatients(criteria, 0, LIST_SEARCH_LIMIT, locale));
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
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PatientResponseDTO> createPatientByReceptionist(
        @Valid @RequestBody PatientRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        Locale locale = parseLocale(lang);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, dto.getHospitalId(), true);
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
    @PreAuthorize(PATIENT_READ_ROLES)
    public ResponseEntity<PatientResponseDTO> getPatientById(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID hospitalId,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        Locale locale = parseLocale(lang);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        return ResponseEntity.ok(patientService.getPatientById(id, resolvedHospitalId, locale));
    }

    // ----------------------------------------------------------
    // Update
    // ----------------------------------------------------------
    @WriteAudited(skip = true, reason = "service emits PATIENT_UPDATE / PATIENT_DELETE")
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
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, dto.getHospitalId(), false);
        dto.setHospitalId(resolvedHospitalId);
        return ResponseEntity.ok(patientService.updatePatient(id, dto, locale));
    }

    /**
     * E8 #54 — restrict or lift the restriction on a chart. An administrative
     * act (HOSPITAL_ADMIN / SUPER_ADMIN, D5-consistent); the write-audit
     * interceptor records it by convention.
     */
    @Operation(summary = "Restrict or lift the restriction on a patient's chart",
        description = "While restricted, every read of the chart needs a live break-the-glass session with a stated reason.")
    @PostMapping("/{id}/chart-restriction")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PatientResponseDTO> setChartRestriction(
        @PathVariable UUID id,
        @Valid @RequestBody ChartRestrictionRequestDTO dto,
        Authentication auth
    ) {
        UUID actor = authUtils.resolveUserId(auth).orElse(null);
        UUID hospitalId = authUtils.resolveHospitalScope(auth, null, false);
        return ResponseEntity.ok(patientService.setChartRestriction(id, dto, actor, hospitalId));
    }

    @WriteAudited(skip = true, reason = "service emits PATIENT_UPDATE / PATIENT_DELETE")
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PatientResponseDTO> patchPatient(
        @PathVariable UUID id,
        @Valid @RequestBody PatientProfileUpdateRequestDTO dto,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        // RCPT-CLIN: clinical text the desk cannot read is not the desk's to overwrite.
        if (com.example.hms.controller.support.ReceptionistResponseShaping.isReceptionistOnly()
            && (dto.getCareTeamNotes() != null || dto.getChronicConditions() != null || dto.getBloodType() != null
                || dto.getMedicalHistorySummary() != null)) {
            throw new org.springframework.security.access.AccessDeniedException("Clinical fields are not the desk's to write");
        }
        Locale locale = parseLocale(lang);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, null, false);
        return ResponseEntity.ok(patientService.patchPatient(id, dto, resolvedHospitalId, locale));
    }

    // ----------------------------------------------------------
    // Delete
    // ----------------------------------------------------------
    @WriteAudited(skip = true, reason = "service emits PATIENT_UPDATE / PATIENT_DELETE")
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
    // The identifiers travel in the body, never in the URL (proxy logs, browser
    // history, front-end tracing). Paging and the hospital scope identify no one.
    @com.example.hms.security.ReadsOnly("a patient search, under POST so its identifiers stay out of the URL")
    @PostMapping("/search")
    @PreAuthorize(PATIENT_PICKER_ROLES)
    public ResponseEntity<List<PatientResponseDTO>> searchPatients(
        @RequestBody(required = false) PatientSearchRequestDTO body,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "true") boolean active,
        @RequestParam(defaultValue = "false") boolean includeInactive,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        Locale locale = parseLocale(lang);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        // QA D1019 / D1274: a number, a birth date or an e-mail typed into the
        // name box is searched as such by the service (each only when its own
        // filter is not given); dob accepts yyyy-mm-dd, dd/mm/yyyy, dd-mm-yyyy.
        PatientSearchRequestDTO terms = body != null ? body : new PatientSearchRequestDTO(null, null, null, null, null);
        // QA D3753: no criterion at all is refused, never answered with the hospital's first
        // patients; the search box (the name) is the field the form marks.
        if (java.util.stream.Stream.of(terms.mrn(), terms.name(), terms.dob(), terms.phone(), terms.email())
                .allMatch(value -> value == null || value.isBlank())) {
            throw new com.example.hms.exception.FieldBusinessException("name", "patient.search.criterionRequired");
        }
        PatientSearchCriteria criteria = PatientSearchCriteria.builder()
            .mrn(terms.mrn())
            .name(terms.name())
            .dateOfBirth(terms.dob())
            .phone(terms.phone())
            .email(terms.email())
            .hospitalId(resolvedHospitalId)
            .active(active)
            .includeInactive(includeInactive)
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
    // The identifiers travel in the body, never in the URL.
    @com.example.hms.security.ReadsOnly("a patient lookup, under POST so its identifiers stay out of the URL")
    @PostMapping("/lookup")
    @PreAuthorize(PATIENT_PICKER_ROLES)
    public ResponseEntity<List<PatientResponseDTO>> lookupPatients(
        @RequestBody(required = false) PatientLookupRequestDTO body,
        @RequestParam(required = false) UUID hospitalId,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        Locale locale = parseLocale(lang);
        PatientLookupRequestDTO terms = body != null ? body
            : new PatientLookupRequestDTO(null, null, null, null, null, null);
        // "mri" is the legacy name of "mrn", read only when "mrn" is blank.
        String effectiveMrn = (terms.mrn() != null && !terms.mrn().isBlank()) ? terms.mrn() : terms.mri();
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        List<PatientResponseDTO> list = patientService.lookupPatients(terms.identifier(), terms.email(),
            terms.phone(), terms.username(), effectiveMrn, resolvedHospitalId, locale);
        return ResponseEntity.ok(list);
    }

    // ----------------------------------------------------------
    // Registration-time existing-patient match (cross-hospital)
    // ----------------------------------------------------------
    @Operation(
        summary = "Exact email/phone match against ALL hospitals for the registration form",
        description = "Returns a masked, privacy-minimal projection so the desk can link an existing patient "
            + "to this hospital (POST /registrations) instead of creating a duplicate record.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "200", description = "Match check completed",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = RegistrationMatchDTO.class)))
    // What the form has typed travels in the body, never in the URL.
    @com.example.hms.security.ReadsOnly("the registration form's duplicate check, under POST so its identifiers"
        + " stay out of the URL")
    @PostMapping("/registration-match")
    // Every role that can register a patient (POST /patients) must be able to run
    // the anti-duplicate match check, or the feature silently never fires for them.
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_NURSE','ROLE_MIDWIFE','ROLE_SUPER_ADMIN')")
    public ResponseEntity<List<RegistrationMatchDTO>> registrationMatch(
        // QA D1061 / D1064: the form's name and date of birth, to tell a shared
        // contact from the same person and to find that person by identity.
        @RequestBody(required = false) RegistrationMatchRequestDTO body,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        RegistrationMatchRequestDTO terms = body != null ? body
            : new RegistrationMatchRequestDTO(null, null, null, null, null);
        return ResponseEntity.ok(patientService.findRegistrationMatches(terms.email(), terms.phone(),
            terms.firstName(), terms.lastName(), terms.dateOfBirth(), resolvedHospitalId));
    }

    @WriteAudited(skip = true, reason = "a read under POST; the service records the access itself")
    @Operation(
        summary = "Doctor consolidated record view",
        description = "Provides a comprehensive patient record snapshot with sensitive data gating and audit logging.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @com.example.hms.security.ReadsOnly("a chart read under POST for its access reason; only the access audit is written")
    @PostMapping("/{id}/doctor-record")
    @PreAuthorize("hasAuthority('ROLE_DOCTOR')")
    public ResponseEntity<DoctorPatientRecordDTO> getDoctorRecord(
        @PathVariable UUID id,
        @Valid @RequestBody DoctorPatientRecordRequestDTO request,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID userId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, request.getHospitalId(), false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
        }
        UserRoleHospitalAssignment assignment = resolveDoctorAssignment(userId, resolvedHospitalId);
        DoctorPatientRecordDTO response = patientService
            .getDoctorRecord(id, resolvedHospitalId, userId, assignment, request);
        return ResponseEntity.ok(response);
    }

    @WriteAudited(skip = true, reason = "a read under POST; the service records the access itself")
    @Operation(
        summary = "Doctor timeline view",
        description = "Provides a longitudinal patient timeline with sensitive data gating and audit logging.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @com.example.hms.security.ReadsOnly("a timeline read under POST for its access reason; only the access audit is written")
    @PostMapping("/{id}/doctor-timeline")
    @PreAuthorize("hasAuthority('ROLE_DOCTOR')")
    public ResponseEntity<PatientTimelineResponseDTO> getDoctorTimeline(
        @PathVariable UUID id,
        @Valid @RequestBody PatientTimelineAccessRequestDTO request,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID userId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, request.getHospitalId(), false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
        }
        UserRoleHospitalAssignment assignment = resolveDoctorAssignment(userId, resolvedHospitalId);
        PatientTimelineResponseDTO response = patientService
            .getDoctorTimeline(id, resolvedHospitalId, userId, assignment, request);
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Superseded addresses for one patient (Tier 2 item 38)",
        description = "Each entry was the patient's address until its replacedAt moment; newest "
            + "move first. Gate mirrors the chart read set so every role that can open the "
            + "demographics card can use its button.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{id}/address-history")
    @PreAuthorize(PATIENT_READ_ROLES)
    public ResponseEntity<java.util.List<com.example.hms.payload.dto.PatientAddressHistoryDTO>> getAddressHistory(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
        }
        return ResponseEntity.ok(patientService.getAddressHistory(id, resolvedHospitalId));
    }

    @Operation(
        summary = "List structured allergy entries for prescribing",
        description = "Provides an audit-friendly list of allergy entries scoped to the clinician's hospital.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
@GetMapping("/{id}/allergies")
    @PreAuthorize(ALLERGY_READ_ROLES)
    public ResponseEntity<List<PatientAllergyResponseDTO>> getPatientAllergies(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID userId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("request.userContext.unavailable"));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
        }
        List<PatientAllergyResponseDTO> allergies = patientService
            .getPatientAllergies(id, resolvedHospitalId, userId);
        // QA D504: a list narrowed by the patient's sharing opt-out must not read
        // as the whole record. The header names the fact, never the hidden rows.
        if (patientService.foreignAllergiesWithheld(id, resolvedHospitalId, userId)) {
            return ResponseEntity.ok()
                .header(RECORDS_WITHHELD_HEADER, RECORDS_WITHHELD_OTHER_HOSPITALS)
                .body(allergies);
        }
        return ResponseEntity.ok(allergies);
    }

    /**
     * QA D504: any clinical list narrowed by the patient's sharing opt-out
     * carries {@code X-Records-Withheld}, so it never reads as the whole
     * record. The header names the fact, never the hidden rows.
     */
    private org.springframework.http.HttpHeaders withheldHeaders(UUID patientId, UUID hospitalId, UUID userId) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (patientService.foreignRecordsWithheld(patientId, hospitalId, userId)) {
            headers.add(RECORDS_WITHHELD_HEADER, RECORDS_WITHHELD_OTHER_HOSPITALS);
        }
        return headers;
    }

    @Operation(
        summary = "Create allergy entry",
        description = "Allows clinicians to capture a structured allergy entry within their hospital context.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{id}/allergies")
    @PreAuthorize(ALLERGY_WRITE_ROLES)
    public ResponseEntity<PatientAllergyResponseDTO> createPatientAllergy(
        @PathVariable UUID id,
        @Valid @RequestBody PatientAllergyRequestDTO request,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID requesterUserId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, request.getHospitalId(), false);
        UUID effectiveHospitalId = resolvedHospitalId != null ? resolvedHospitalId : request.getHospitalId();
        if (effectiveHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
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
    @PreAuthorize(ALLERGY_WRITE_ROLES)
    public ResponseEntity<PatientAllergyResponseDTO> updatePatientAllergy(
        @PathVariable UUID id,
        @PathVariable UUID allergyId,
        @Valid @RequestBody PatientAllergyRequestDTO request,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID requesterUserId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, request.getHospitalId(), false);
        UUID effectiveHospitalId = resolvedHospitalId != null ? resolvedHospitalId : request.getHospitalId();
        if (effectiveHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
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
    @PreAuthorize(ALLERGY_WRITE_ROLES)
    public ResponseEntity<MessageResponse> deactivatePatientAllergy(
        @PathVariable UUID id,
        @PathVariable UUID allergyId,
        @RequestParam(required = false) UUID hospitalId,
        @Valid @RequestBody PatientAllergyDeactivateRequestDTO request,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID requesterUserId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("patient.r7.allergyDeactivateHospitalRequired");
        }
        patientService.deactivatePatientAllergy(id, resolvedHospitalId, allergyId, requesterUserId, request.getReason());
        return ResponseEntity.ok(new MessageResponse(com.example.hms.utility.MessageUtil.resolve("patient.r25.allergyDeactivated")));
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
    // E9 #69: the pharmacist verifying a prescription (V139) reads the problem list.
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST')")
    public ResponseEntity<List<PatientProblemResponseDTO>> listPatientDiagnoses(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(name = "includeHistorical", defaultValue = "false") boolean includeHistorical,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
        }
        List<PatientProblemResponseDTO> diagnoses = patientService
            .listPatientDiagnoses(id, resolvedHospitalId, includeHistorical);
        // QA D504: a problem list narrowed by the patient's opt-out says so.
        return ResponseEntity.ok()
            .headers(withheldHeaders(id, resolvedHospitalId, authUtils.resolveUserId(auth).orElse(null)))
            .body(diagnoses);
    }

    @Operation(
        summary = "Create patient diagnosis",
        description = "Allows doctors to capture a new diagnosis entry with supporting evidence and coding.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{id}/diagnoses")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR')")
    public ResponseEntity<PatientProblemResponseDTO> createPatientDiagnosis(
        @PathVariable UUID id,
        @Valid @RequestBody PatientDiagnosisRequestDTO request,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID requesterUserId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, request.getHospitalId(), false);
        UUID effectiveHospitalId = resolvedHospitalId != null ? resolvedHospitalId : request.getHospitalId();
        if (effectiveHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
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
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR')")
    public ResponseEntity<PatientProblemResponseDTO> updatePatientDiagnosis(
        @PathVariable UUID id,
        @PathVariable UUID diagnosisId,
        @Valid @RequestBody PatientDiagnosisUpdateRequestDTO request,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID requesterUserId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, request.getHospitalId(), false);
        UUID effectiveHospitalId = resolvedHospitalId != null ? resolvedHospitalId : request.getHospitalId();
        if (effectiveHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
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
    @PreAuthorize(DIAGNOSIS_REMOVE_ROLES)
    public ResponseEntity<MessageResponse> deletePatientDiagnosis(
        @PathVariable UUID id,
        @PathVariable UUID diagnosisId,
        @RequestParam(required = false) UUID hospitalId,
        @Valid @RequestBody PatientDiagnosisDeleteRequestDTO request,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID requesterUserId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(MSG_DOCTOR_CONTEXT_REQUIRED));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("patient.r7.diagnosisRemoveHospitalRequired");
        }
        patientService.deletePatientDiagnosis(id, resolvedHospitalId, diagnosisId, requesterUserId, request.getReason());
        return ResponseEntity.ok(new MessageResponse(com.example.hms.utility.MessageUtil.resolve("patient.r25.diagnosisRemoved")));
    }

    @Operation(
        summary = "List patient chart updates",
        description = "Paginated history of clinician-authored chart updates for the specified patient.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{id}/chart-updates")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<Page<PatientChartUpdateResponseDTO>> listPatientChartUpdates(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID hospitalId,
        @PageableDefault(size = 20, sort = "versionNumber", direction = Sort.Direction.DESC) Pageable pageable,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID userId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("request.userContext.unavailable"));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
        }
        resolveClinicalAssignment(userId, resolvedHospitalId);
        Page<PatientChartUpdateResponseDTO> response = patientChartUpdateService
            .listPatientChartUpdates(id, resolvedHospitalId, pageable);
        // QA D504: chart updates narrowed by the patient's opt-out say so.
        return ResponseEntity.ok().headers(withheldHeaders(id, resolvedHospitalId, userId)).body(response);
    }

    @Operation(
        summary = "Get patient chart update",
        description = "Fetch a single chart update by its identifier.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{patientId}/chart-updates/{updateId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<PatientChartUpdateResponseDTO> getPatientChartUpdate(
        @PathVariable UUID patientId,
        @PathVariable UUID updateId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID userId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("request.userContext.unavailable"));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (resolvedHospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
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
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','NURSE','MIDWIFE')")
    public ResponseEntity<PatientChartUpdateResponseDTO> createPatientChartUpdate(
        @PathVariable UUID patientId,
        @Valid @RequestBody DoctorPatientChartUpdateRequestDTO request,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID userId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("request.userContext.unavailable"));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, request.getHospitalId(), false);
        if (resolvedHospitalId == null && request.getHospitalId() == null) {
            throw new BusinessException("request.hospitalContext.required");
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
            throw new BusinessException("request.day.invalid");
        }
    }

    private UserRoleHospitalAssignment resolveDoctorAssignment(UUID userId, UUID hospitalId) {
        return assignmentRepository
            .findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, hospitalId, "ROLE_DOCTOR")
            .orElseThrow(() -> new BusinessException("request.clinicianAssignment.unavailable"));
    }

    private UserRoleHospitalAssignment resolveClinicalAssignment(UUID userId, UUID hospitalId) {
        if (userId == null || hospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
        }
        for (String role : CLINICAL_CHART_UPDATE_ROLES) {
            Optional<UserRoleHospitalAssignment> assignment = assignmentRepository
                .findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, hospitalId, role);
            if (assignment.isPresent()) {
                return assignment.get();
            }
        }
        throw new BusinessException("request.clinicianAssignment.unavailable");
    }

}
