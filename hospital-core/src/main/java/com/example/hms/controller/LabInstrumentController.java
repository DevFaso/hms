package com.example.hms.controller;

import com.example.hms.payload.dto.LabInstrumentRequestDTO;
import com.example.hms.payload.dto.LabInstrumentResponseDTO;
import com.example.hms.service.LabInstrumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/lab/instruments")
@Tag(name = "Lab Instrument Management", description = "Endpoints for managing lab instruments")
@RequiredArgsConstructor
public class LabInstrumentController {

    private final LabInstrumentService instrumentService;

    @Operation(summary = "List instruments for a hospital")
    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_LAB_TECHNICIAN','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<Page<LabInstrumentResponseDTO>> list(
            @PathVariable UUID hospitalId,
            @ParameterObject Pageable pageable,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(instrumentService.getByHospital(hospitalId, pageable, locale));
    }

    @Operation(summary = "Get instrument by ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_LAB_TECHNICIAN','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<LabInstrumentResponseDTO> getById(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(instrumentService.getById(id, locale));
    }

    @Operation(summary = "Register a new instrument")
    @PostMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<LabInstrumentResponseDTO> create(
            @PathVariable UUID hospitalId,
            @Valid @RequestBody LabInstrumentRequestDTO dto,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(instrumentService.create(hospitalId, dto, locale));
    }

    @Operation(summary = "Update an instrument")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<LabInstrumentResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody LabInstrumentRequestDTO dto,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(instrumentService.update(id, dto, locale));
    }

    @Operation(summary = "Deactivate an instrument")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_LAB_DIRECTOR','ROLE_LAB_MANAGER','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        instrumentService.deactivate(id, locale);
        return ResponseEntity.noContent().build();
    }

    private static Locale parseLocale(String lang) {
        if (lang == null || lang.isBlank()) return Locale.ENGLISH;
        String primary = lang.split(",")[0].split(";")[0].trim();
        return Locale.forLanguageTag(primary);
    }
}
