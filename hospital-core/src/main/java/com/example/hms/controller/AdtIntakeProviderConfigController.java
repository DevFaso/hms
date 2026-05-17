package com.example.hms.controller;

import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigRequestDTO;
import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigResponseDTO;
import com.example.hms.service.platform.AdtIntakeProviderConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/adt-intake-configs")
@RequiredArgsConstructor
@Tag(
    name = "ADT Intake Provider Configs",
    description = "Admin CRUD for the per-hospital ADT auto-create defaults"
        + " consumed by MllpInboundAdtVisitProjectionService (roadmap row 24 admin UI)")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class AdtIntakeProviderConfigController {

    private final AdtIntakeProviderConfigService service;

    @GetMapping
    @Operation(summary = "List intake configs (optionally filter by hospital)")
    public ResponseEntity<List<AdtIntakeProviderConfigResponseDTO>> list(
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId
    ) {
        if (hospitalId == null) {
            return ResponseEntity.ok(service.findAll());
        }
        Optional<AdtIntakeProviderConfigResponseDTO> byHospital = service.findByHospital(hospitalId);
        return byHospital
            .map(dto -> ResponseEntity.ok(List.of(dto)))
            .orElseGet(() -> ResponseEntity.ok(List.of()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an intake config by id")
    public ResponseEntity<AdtIntakeProviderConfigResponseDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id, LocaleContextHolder.getLocale()));
    }

    @PostMapping
    @Operation(
        summary = "Upsert the intake config for a hospital",
        description = "Idempotent on hospitalId — replaces any existing row in-place"
            + " (one row per hospital is enforced by uk_adt_intake_config_hospital)."
    )
    public ResponseEntity<AdtIntakeProviderConfigResponseDTO> upsert(
        @Valid @RequestBody AdtIntakeProviderConfigRequestDTO request
    ) {
        Locale locale = LocaleContextHolder.getLocale();
        AdtIntakeProviderConfigResponseDTO saved = service.upsert(request, locale);
        return ResponseEntity
            .created(URI.create("/admin/adt-intake-configs/" + saved.id()))
            .body(saved);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an intake config (auto-create reverts to disabled for the hospital)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id, LocaleContextHolder.getLocale());
        return ResponseEntity.noContent().build();
    }
}
