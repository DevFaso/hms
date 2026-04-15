package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.EncounterRequestDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.EncounterNoteRequestDTO;
import com.example.hms.payload.dto.EncounterNoteResponseDTO;
import com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO;
import com.example.hms.payload.dto.EncounterNoteAddendumResponseDTO;
import com.example.hms.payload.dto.EncounterNoteHistoryResponseDTO;
import com.example.hms.payload.dto.NursingIntakeRequestDTO;
import com.example.hms.payload.dto.NursingIntakeResponseDTO;
import com.example.hms.payload.dto.TriageSubmissionRequestDTO;
import com.example.hms.payload.dto.TriageSubmissionResponseDTO;
import com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO;
import com.example.hms.payload.dto.clinical.CheckOutRequestDTO;
import com.example.hms.service.EncounterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping(
    value = "/encounters",
    produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "Encounter Management", description = "APIs for managing patient encounters")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class EncounterController {

    private final EncounterService encounterService;
    private final ControllerAuthUtils authUtils;

    // ----------------------------------------------------------
    // Create (Receptionist can check-in → default ARRIVED)
    // ----------------------------------------------------------
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
    @Operation(
        summary = "Create a new encounter",
        description = "Creates a new patient encounter. " +
            "Receptionists use this as the walk-in check-in endpoint: the hospitalId is always " +
            "taken from the JWT (cannot be overridden) and the initial status defaults to ARRIVED. " +
            "For receptionist walk-ins, omit appointmentId; supply patientId and chiefComplaint. " +
            "Doctors, nurses, and midwives may also create encounters; their hospitalId defaults " +
            "to the JWT value but can be overridden in the request body."
    )
    public ResponseEntity<EncounterResponseDTO> create(
        @Valid @RequestBody EncounterRequestDTO dto,
        Locale locale,
        Authentication auth
    ) {
        // Enforce hospital scoping
        UUID jwtHospitalId = authUtils.extractHospitalIdFromJwt(auth);
        boolean isSuperAdmin = authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN");
        boolean isReceptionist = !isSuperAdmin && authUtils.hasAuthority(auth, "ROLE_RECEPTIONIST");
        if (isReceptionist) {
            if (jwtHospitalId == null) {
                throw new BusinessException("Receptionist must be affiliated with a hospital (missing hospitalId in token).");
            }
            dto.setHospitalId(jwtHospitalId); // receptionist cannot override
        } else {
            // non-receptionist: allow body value; fallback to JWT if body missing
            if (dto.getHospitalId() == null) {
                dto.setHospitalId(jwtHospitalId);
            }
        }

        // Default status for front-desk check-in
        if (dto.getStatus() == null) {
            dto.setStatus(EncounterStatus.ARRIVED);
        }
        // Optional: set encounter time if your DTO supports it
        if (dto.getEncounterDate() == null) {
            dto.setEncounterDate(LocalDateTime.now());
        }

        EncounterResponseDTO created = encounterService.createEncounter(dto, locale);
        return ResponseEntity
            .created(URI.create("/encounters/" + created.getId()))
            .body(created);
    }

    // ----------------------------------------------------------
    // Read by ID
    // ----------------------------------------------------------
    @GetMapping(value = "/{id}", consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_PATIENT','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get an encounter by ID")
    public ResponseEntity<EncounterResponseDTO> getById(
        @PathVariable UUID id,
        Locale locale
    ) {
        // NOTE: If patients can call this, ensure service enforces "own encounter only".
        return ResponseEntity.ok(encounterService.getEncounterById(id, locale));
    }

    // ----------------------------------------------------------
    // Paged & filtered list
    // ----------------------------------------------------------
    @GetMapping(consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Search/List encounters")
    public ResponseEntity<Page<EncounterResponseDTO>> list(
        @RequestParam(required = false) UUID patientId,
        @RequestParam(required = false) UUID staffId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
        @RequestParam(required = false) EncounterStatus status,
        @ParameterObject @PageableDefault(size = 20, sort = "encounterDate", direction = org.springframework.data.domain.Sort.Direction.DESC)
        Pageable pageable,
        Locale locale,
        Authentication auth
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            return ResponseEntity.badRequest().build();
        }

        boolean isSuperAdmin = authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN");
        UUID jwtHospitalId = authUtils.extractHospitalIdFromJwt(auth);
        UUID resolvedHospitalId = hospitalId;

        if (!isSuperAdmin) {
            // Non-superadmin MUST be scoped to a hospital — try param → JWT → assignment fallback
            if (resolvedHospitalId == null) {
                resolvedHospitalId = jwtHospitalId;
            }
            if (resolvedHospitalId == null) {
                resolvedHospitalId = authUtils.fallbackHospitalFromAssignments(auth).orElse(null);
            }
            if (resolvedHospitalId == null) {
                throw new BusinessException("Hospital context required to list encounters. Please select an active hospital.");
            }
        } else {
            // super-admin: allow unscoped (null = global/all)
            // resolvedHospitalId stays null intentionally
        }

        Page<EncounterResponseDTO> page =
            encounterService.list(patientId, staffId, resolvedHospitalId, from, to, status, pageable, locale);
        return ResponseEntity.ok(page);
    }

    // ----------------------------------------------------------
    // Update
    // ----------------------------------------------------------
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Update an encounter (super-admin, doctor, nurse, midwife, hospital-admin)")
    public ResponseEntity<EncounterResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody EncounterRequestDTO dto,
        Locale locale,
        Authentication auth
    ) {
        // For consistency, honor JWT hospital for receptionist (though receptionist isn't allowed here)
        UUID jwtHospitalId = authUtils.extractHospitalIdFromJwt(auth);
        if (dto.getHospitalId() == null) {
            dto.setHospitalId(jwtHospitalId);
        }
        return ResponseEntity.ok(encounterService.updateEncounter(id, dto, locale));
    }

    // ----------------------------------------------------------
    // Delete
    // ----------------------------------------------------------
    @DeleteMapping(value = "/{id}", consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Hard delete an encounter (Super Admin)")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Locale locale) {
        encounterService.deleteEncounter(id, locale);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------
    // By Doctor
    // ----------------------------------------------------------
    @GetMapping(value = "/doctor/{identifier}", consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get encounters by doctor (UUID | username | email | license)")
    public ResponseEntity<List<EncounterResponseDTO>> byDoctor(
        @PathVariable String identifier,
        Locale locale
    ) {
        return ResponseEntity.ok(
            encounterService.getEncountersByDoctorIdentifier(identifier, locale)
        );
    }

    // ----------------------------------------------------------
    // MVP 2 — Triage Submission
    // ----------------------------------------------------------
    @PostMapping(value = "/{encounterId}/triage", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_NURSE','ROLE_DOCTOR')")
    @Operation(
        summary = "Submit triage data for an encounter (MVP 2)",
        description = "Atomically records vitals, chief complaint, ESI acuity, room assignment, "
            + "and transitions the encounter from ARRIVED → WAITING_FOR_PHYSICIAN."
    )
    public ResponseEntity<TriageSubmissionResponseDTO> submitTriage(
        @PathVariable UUID encounterId,
        @Valid @RequestBody TriageSubmissionRequestDTO request,
        Authentication auth
    ) {
        String username = auth.getName();
        TriageSubmissionResponseDTO response = encounterService.submitTriage(encounterId, request, username);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(response);
    }

    // ----------------------------------------------------------
    // Start Encounter — Doctor picks up a WAITING_FOR_PHYSICIAN patient
    // ----------------------------------------------------------
    @PostMapping("/{encounterId}/start")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_DOCTOR')")
    @Operation(
        summary = "Start an encounter (doctor picks up patient)",
        description = "Transitions a WAITING_FOR_PHYSICIAN encounter to IN_PROGRESS."
    )
    public ResponseEntity<EncounterResponseDTO> startEncounter(
        @PathVariable UUID encounterId,
        Authentication auth
    ) {
        EncounterResponseDTO response = encounterService.startEncounter(encounterId, auth.getName());
        return ResponseEntity.ok(response);
    }

    // ----------------------------------------------------------
    // MVP 3 — Nursing Intake Flowsheet
    // ----------------------------------------------------------
    @PostMapping(value = "/{encounterId}/nursing-intake", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_NURSE','ROLE_DOCTOR')")
    @Operation(
        summary = "Submit nursing intake data for an encounter (MVP 3)",
        description = "Atomically records allergy reconciliation, medication reconciliation, "
            + "nursing assessment notes, pain assessment, and fall risk detail."
    )
    public ResponseEntity<NursingIntakeResponseDTO> submitNursingIntake(
        @PathVariable UUID encounterId,
        @Valid @RequestBody NursingIntakeRequestDTO request,
        Authentication auth
    ) {
        String username = auth.getName();
        NursingIntakeResponseDTO response = encounterService.submitNursingIntake(encounterId, request, username);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(response);
    }

    // ----------------------------------------------------------
    // Encounter Notes Management
    // ----------------------------------------------------------

    // ----------------------------------------------------------
    // MVP 6 — Check-Out & After-Visit Summary
    // ----------------------------------------------------------
    @PostMapping(value = "/{encounterId}/checkout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST')")
    @Operation(
        summary = "Check out a patient and generate After-Visit Summary (MVP 6)",
        description = "Atomically transitions encounter → COMPLETED, linked appointment → COMPLETED, "
            + "records checkout timestamp, discharge diagnoses, follow-up instructions, "
            + "and returns a comprehensive After-Visit Summary."
    )
    public ResponseEntity<AfterVisitSummaryDTO> checkOut(
        @PathVariable UUID encounterId,
        @Valid @RequestBody CheckOutRequestDTO request,
        Authentication auth
    ) {
        String username = auth.getName();
        AfterVisitSummaryDTO avs = encounterService.checkOut(encounterId, request, username);
        return ResponseEntity.ok(avs);
    }

    // ----------------------------------------------------------
    // MVP 6 — Retrieve After-Visit Summary for a completed encounter
    // ----------------------------------------------------------
    @GetMapping(value = "/{encounterId}/avs", consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST','ROLE_PATIENT')")
    @Operation(
        summary = "Get After-Visit Summary for a completed encounter (MVP 6)",
        description = "Returns the AVS for a previously checked-out encounter. "
            + "Patients may access their own encounter's AVS."
    )
    public ResponseEntity<AfterVisitSummaryDTO> getAfterVisitSummary(
        @PathVariable UUID encounterId,
        Locale locale
    ) {
        AfterVisitSummaryDTO avs = encounterService.getAfterVisitSummary(encounterId);
        return ResponseEntity.ok(avs);
    }
    @PostMapping(value = "/{encounterId}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Create or update encounter note (super-admin, doctor, nurse, midwife, hospital-admin)", 
               description = "Creates or updates the encounter note with SOAP/narrative documentation")
    public ResponseEntity<EncounterNoteResponseDTO> upsertEncounterNote(
        @PathVariable UUID encounterId,
        @Valid @RequestBody EncounterNoteRequestDTO request,
        Locale locale
    ) {
        EncounterNoteResponseDTO response = encounterService.upsertEncounterNote(encounterId, request, locale);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/{encounterId}/notes/addendums", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    @Operation(summary = "Add addendum to encounter note",
               description = "Appends a late entry/addendum to an encounter note with timestamp and reason. " +
                           "Original note remains unchanged; addendum is clearly marked.")
    public ResponseEntity<EncounterNoteAddendumResponseDTO> addEncounterNoteAddendum(
        @PathVariable UUID encounterId,
        @Valid @RequestBody EncounterNoteAddendumRequestDTO request,
        Locale locale
    ) {
        EncounterNoteAddendumResponseDTO response = encounterService.addEncounterNoteAddendum(encounterId, request, locale);
        return ResponseEntity
            .created(URI.create("/encounters/" + encounterId + "/notes/addendums"))
            .body(response);
    }

    @GetMapping(value = "/{encounterId}/notes/history", consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get encounter note history",
               description = "Returns the audit trail of all changes to the encounter note, " +
                           "including original creation, updates, and addendums.")
    public ResponseEntity<List<EncounterNoteHistoryResponseDTO>> getEncounterNoteHistory(
        @PathVariable UUID encounterId,
        Locale locale
    ) {
        List<EncounterNoteHistoryResponseDTO> history = encounterService.getEncounterNoteHistory(encounterId, locale);
        return ResponseEntity.ok(history);
    }

}

