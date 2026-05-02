package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.SmartPhraseRequestDTO;
import com.example.hms.payload.dto.SmartPhraseResponseDTO;
import com.example.hms.service.SmartPhraseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/smart-phrases")
@RequiredArgsConstructor
@Tag(name = "SmartPhrase Library",
     description = "Dot-phrase macro library used by the per-section EncounterNote form (item 5). "
                 + "USER > HOSPITAL > GLOBAL precedence is applied at autocomplete time.")
public class SmartPhraseController {

    private static final String CLINICIAN_ROLES =
        "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')";
    private static final String ADMIN_ROLES =
        "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN')";

    private final SmartPhraseService smartPhraseService;
    private final ControllerAuthUtils authUtils;

    @PostMapping
    @Operation(summary = "Create a SmartPhrase macro",
               description = "Authorization is enforced in the service: GLOBAL requires SUPER_ADMIN, "
                           + "HOSPITAL requires HOSPITAL_ADMIN/SUPER_ADMIN at the target hospital, "
                           + "USER is forced to the authenticated user (any client-supplied ownerUserId "
                           + "is overridden).")
    @ApiResponse(responseCode = "201", description = "Macro created")
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<SmartPhraseResponseDTO> create(
            @Valid @RequestBody SmartPhraseRequestDTO request) {
        SmartPhraseResponseDTO created = smartPhraseService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing SmartPhrase macro",
               description = "Authorization mirrors create: callers may only edit GLOBAL with SUPER_ADMIN, "
                           + "HOSPITAL macros at hospitals where they hold HOSPITAL_ADMIN/SUPER_ADMIN, and "
                           + "USER macros that they own.")
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<SmartPhraseResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody SmartPhraseRequestDTO request) {
        return ResponseEntity.ok(smartPhraseService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a SmartPhrase macro",
               description = "Same authorization gate as update.")
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        smartPhraseService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a SmartPhrase by id")
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<SmartPhraseResponseDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(smartPhraseService.get(id));
    }

    @GetMapping("/global")
    @Operation(summary = "Browse the GLOBAL macro library",
               description = "Admin / library view; ordinary clinicians use /autocomplete instead.")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<Page<SmartPhraseResponseDTO>> listGlobal(
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(smartPhraseService.listGlobal(pageable));
    }

    @GetMapping("/autocomplete")
    @Operation(summary = "Trigger-prefix autocomplete for the calling user",
               description = "Returns macros visible to the caller (USER overrides HOSPITAL overrides GLOBAL). "
                           + "Returns an empty list when prefix does not begin with '.' or is shorter than two "
                           + "characters. The optional hospitalId is validated against the caller's active "
                           + "assignments — non-SUPER_ADMIN callers cannot query a hospital they do not work at.")
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<List<SmartPhraseResponseDTO>> autocomplete(
            @RequestParam(defaultValue = ".") String prefix,
            @RequestParam(required = false) UUID hospitalId,
            Authentication auth) {
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        return ResponseEntity.ok(smartPhraseService.autocomplete(prefix, resolvedHospitalId));
    }

    @PostMapping("/{id}/usage")
    @Operation(summary = "Record that a macro was inserted",
               description = "Fire-and-forget bump of usage_count + last_used_at; the FE calls this when "
                           + "the user accepts an autocomplete suggestion.")
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<Void> recordUsage(@PathVariable UUID id) {
        smartPhraseService.recordUsage(id);
        return ResponseEntity.noContent().build();
    }
}
