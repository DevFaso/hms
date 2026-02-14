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
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Create a new encounter (receptionist-doctor-nurse-admin)")
    public ResponseEntity<EncounterResponseDTO> create(
        @Valid @RequestBody EncounterRequestDTO dto,
        Locale locale,
        Authentication auth
    ) {
        // Enforce hospital scoping
        UUID jwtHospitalId = authUtils.extractHospitalIdFromJwt(auth);
        boolean isReceptionist = authUtils.hasAuthority(auth, "ROLE_RECEPTIONIST");
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
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_PATIENT','ROLE_RECEPTIONIST')")
    @Operation(summary = "Get an encounter by ID")
    public ResponseEntity<EncounterResponseDTO> getById(
        @PathVariable UUID id,
        Locale locale
    ) {
        // NOTE: If patients can call this, ensure service enforces "own encounter only".
        return ResponseEntity.ok(encounterService.getEncounterById(id, locale));
    }

    // ----------------------------------------------------------
    // Paged & filtered list (receptionist auto-scoped to own hospital)
    // ----------------------------------------------------------
    @GetMapping(consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
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

        boolean isReceptionist = authUtils.hasAuthority(auth, "ROLE_RECEPTIONIST");
        UUID jwtHospitalId = authUtils.extractHospitalIdFromJwt(auth);
        UUID resolvedHospitalId = hospitalId;

        if (isReceptionist) {
            // receptionist is always scoped to JWT hospital
            if (jwtHospitalId == null) {
                throw new BusinessException("Receptionist must be affiliated with a hospital (missing hospitalId in token).");
            }
            resolvedHospitalId = jwtHospitalId;
        } else if (resolvedHospitalId == null) {
            // doctors/nurses/admins: fallback to JWT if provided
            resolvedHospitalId = jwtHospitalId;
        }

        Page<EncounterResponseDTO> page =
            encounterService.list(patientId, staffId, resolvedHospitalId, from, to, status, pageable, locale);
        return ResponseEntity.ok(page);
    }

    // ----------------------------------------------------------
    // Update
    // ----------------------------------------------------------
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Update an encounter")
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
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
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
    // Encounter Notes Management
    // ----------------------------------------------------------
    @PostMapping(value = "/{encounterId}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Create or update encounter note", 
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

