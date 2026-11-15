package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.clinical.UnsignedNoteOverdueDTO;
import com.example.hms.security.ActingContext;
import com.example.hms.service.UnsignedNoteFollowUp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Provisional platform rule (unsigned-note workflow): the hospital administrator's list of clinical notes left
 * unsigned for more than 72 hours at their hospital. Author, patient, visit date and age only; no note content,
 * and never an in-basket item for the administrator.
 */
@RestController
@RequestMapping(value = "/unsigned-notes", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Unsigned notes", description = "Hospital administrator's list of clinical notes left unsigned")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class UnsignedNoteController {

    private final UnsignedNoteFollowUp unsignedNotes;
    private final ControllerAuthUtils authUtils;

    @GetMapping(value = "/overdue", consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Notes unsigned for more than 72 hours at the acting hospital, oldest first")
    public ResponseEntity<List<UnsignedNoteOverdueDTO>> overdue(Authentication auth, ActingContext ctx) {
        UUID hospitalId = authUtils.resolveHospitalScope(auth, ctx.hospitalId(), false);
        return ResponseEntity.ok(unsignedNotes.overdueAtHospital(hospitalId));
    }
}
