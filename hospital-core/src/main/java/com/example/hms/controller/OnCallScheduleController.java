package com.example.hms.controller;

import com.example.hms.payload.dto.OnCallScheduleRequestDTO;
import com.example.hms.payload.dto.OnCallScheduleResponseDTO;
import com.example.hms.service.OnCallScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * On-call rota (P2 #13).
 *
 * <p>{@code GET /me/on-call-status} has read {@code OnCallSchedule} since it
 * shipped and NOTHING has ever written to it, so the endpoint has only ever
 * been able to answer "no". An on-call schedule nobody can fill in is an
 * on-call schedule that says everyone is off duty.
 */
@RestController
@RequestMapping(value = "/on-call", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "On-Call Schedule", description = "Maintain the on-call rota")
@SecurityRequirement(name = "bearerAuth")
public class OnCallScheduleController {

    /** Reading the rota is broad — anyone who may need to find cover. */
    private static final String READ_ROLES =
        "hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_RECEPTIONIST',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    /** Writing it is not: a rota anyone can edit is a rota nobody can trust. */
    private static final String WRITE_ROLES =
        "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final OnCallScheduleService onCallScheduleService;

    @GetMapping
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List the on-call rota",
        description = "Defaults to yesterday through seven days ahead when no window is given.")
    public ResponseEntity<List<OnCallScheduleResponseDTO>> list(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(onCallScheduleService.listForHospital(from, to));
    }

    @GetMapping("/staff/{staffId}")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List one staff member's on-call history")
    public ResponseEntity<List<OnCallScheduleResponseDTO>> listForStaff(@PathVariable UUID staffId) {
        return ResponseEntity.ok(onCallScheduleService.listForStaff(staffId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Add an on-call rota entry")
    public ResponseEntity<OnCallScheduleResponseDTO> create(
        @Valid @RequestBody OnCallScheduleRequestDTO request) {
        return new ResponseEntity<>(onCallScheduleService.create(request), HttpStatus.CREATED);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Update an on-call rota entry")
    public ResponseEntity<OnCallScheduleResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody OnCallScheduleRequestDTO request) {
        return ResponseEntity.ok(onCallScheduleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Remove an on-call rota entry")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        onCallScheduleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
