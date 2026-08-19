package com.example.hms.controller;

import com.example.hms.payload.dto.platform.MllpAllowedSenderRequestDTO;
import com.example.hms.payload.dto.platform.MllpAllowedSenderResponseDTO;
import com.example.hms.service.platform.MllpAllowedSenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
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

@RestController
@RequestMapping("/admin/mllp/allowed-senders")
@RequiredArgsConstructor
@Tag(name = "MLLP Allowed Senders",
    description = "Admin CRUD for the per-facility allowlist that gates inbound HL7 v2 MLLP traffic")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class MllpAllowedSenderController {

    private final MllpAllowedSenderService service;

    @GetMapping
    @Operation(summary = "List allowed senders (optionally filtered by hospital)")
    public ResponseEntity<List<MllpAllowedSenderResponseDTO>> list(
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId
    ) {
        Locale locale = LocaleContextHolder.getLocale();
        List<MllpAllowedSenderResponseDTO> body = (hospitalId == null)
            ? service.findAll(locale)
            : service.findByHospital(hospitalId, locale);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an allowed sender by id")
    public ResponseEntity<MllpAllowedSenderResponseDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id, LocaleContextHolder.getLocale()));
    }

    @PostMapping
    @Operation(summary = "Allowlist a new (sendingApplication, sendingFacility) pair against a hospital")
    public ResponseEntity<MllpAllowedSenderResponseDTO> create(
        @Valid @RequestBody MllpAllowedSenderRequestDTO request
    ) {
        MllpAllowedSenderResponseDTO created = service.create(request, LocaleContextHolder.getLocale());
        return ResponseEntity
            .created(URI.create("/admin/mllp/allowed-senders/" + created.id()))
            .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing allowed sender entry")
    public ResponseEntity<MllpAllowedSenderResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody MllpAllowedSenderRequestDTO request
    ) {
        return ResponseEntity.ok(service.update(id, request, LocaleContextHolder.getLocale()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-deactivate an allowed sender entry (sets active=false)")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id, LocaleContextHolder.getLocale());
        return ResponseEntity.noContent().build();
    }
}
