package com.example.hms.controller;

import com.example.hms.payload.dto.nurse.NursingNoteAddendumRequestDTO;
import com.example.hms.payload.dto.nurse.NursingNoteCreateRequestDTO;
import com.example.hms.payload.dto.nurse.NursingNoteResponseDTO;
import com.example.hms.service.NursingNoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/nurse/notes")
@RequiredArgsConstructor
@Validated
@Tag(name = "Nurse Notes", description = "Structured nursing documentation workflows")
public class NursingNoteController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final NursingNoteService nursingNoteService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Document a structured nursing note for a patient")
    public ResponseEntity<NursingNoteResponseDTO> createNote(
        @Valid @RequestBody NursingNoteCreateRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        NursingNoteResponseDTO response = nursingNoteService.createNote(request, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{noteId}/addenda")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Append an auditable addendum to an existing nursing note")
    public ResponseEntity<NursingNoteResponseDTO> appendAddendum(
        @PathVariable UUID noteId,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        @Valid @RequestBody NursingNoteAddendumRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        NursingNoteResponseDTO response = nursingNoteService.appendAddendum(noteId, hospitalId, request, locale);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Load recent nursing notes for a patient")
    public ResponseEntity<List<NursingNoteResponseDTO>> getRecentNotes(
        @RequestParam(name = "patientId") UUID patientId,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        @RequestParam(name = "limit", required = false) Integer limit,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : clamp(limit, 1, MAX_LIMIT);
        List<NursingNoteResponseDTO> notes = nursingNoteService.getRecentNotes(patientId, hospitalId, effectiveLimit, locale);
        return ResponseEntity.ok(notes);
    }

    @GetMapping("/{noteId}")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Fetch a specific nursing note")
    public ResponseEntity<NursingNoteResponseDTO> getNote(
        @PathVariable UUID noteId,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        NursingNoteResponseDTO response = nursingNoteService.getNote(noteId, hospitalId, locale);
        return ResponseEntity.ok(response);
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
