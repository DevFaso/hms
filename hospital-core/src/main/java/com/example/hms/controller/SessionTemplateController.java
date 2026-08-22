package com.example.hms.controller;

import com.example.hms.payload.dto.scheduling.SessionTemplateRequestDTO;
import com.example.hms.payload.dto.scheduling.SessionTemplateResponseDTO;
import com.example.hms.service.scheduling.SessionTemplateService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Session templates (P2 #11): the recurring clinic sessions the slot generator
 * materialises. With no writer for this table, {@code POST /slots/generate}
 * could only ever answer {@code slotsCreated=0}.
 */
@RestController
@RequestMapping(value = "/session-templates", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Session Templates", description = "Recurring clinic sessions that generate bookable slots")
@SecurityRequirement(name = "bearerAuth")
public class SessionTemplateController {

    private static final String READ_ROLES =
        "hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private static final String ADMIN_ROLES =
        "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final SessionTemplateService sessionTemplateService;

    @GetMapping
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List session templates for the caller's hospital")
    public ResponseEntity<List<SessionTemplateResponseDTO>> list(
        @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(sessionTemplateService.list(includeInactive));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Create a session template")
    public ResponseEntity<SessionTemplateResponseDTO> create(
        @Valid @RequestBody SessionTemplateRequestDTO request) {
        return new ResponseEntity<>(sessionTemplateService.create(request), HttpStatus.CREATED);
    }

    /**
     * Editing changes what FUTURE generation produces; slots already
     * materialised are deliberately untouched, so editing next month's hours
     * cannot silently move an appointment already booked for next week.
     */
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Update a session template")
    public ResponseEntity<SessionTemplateResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody SessionTemplateRequestDTO request) {
        return ResponseEntity.ok(sessionTemplateService.update(id, request));
    }

    @PutMapping("/{id}/deactivate")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Retire a session template",
        description = "Stops future generation from this template. Already-generated slots are untouched.")
    public ResponseEntity<SessionTemplateResponseDTO> deactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(sessionTemplateService.deactivate(id));
    }

    @PutMapping("/{id}/reactivate")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Reactivate a retired session template")
    public ResponseEntity<SessionTemplateResponseDTO> reactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(sessionTemplateService.reactivate(id));
    }
}
