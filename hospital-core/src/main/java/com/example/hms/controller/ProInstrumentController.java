package com.example.hms.controller;

import com.example.hms.payload.dto.pro.ProInstrumentDefinitionDTO;
import com.example.hms.payload.dto.pro.ProInstrumentViewDTO;
import com.example.hms.service.pro.ProInstrumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Standardized PRO instrument definitions (Tier 2 item 47). Reading is
 * clinical; loading is SUPER_ADMIN only — the person importing is
 * vouching that the text and option scores are the validated ones.
 */
@RestController
@RequestMapping("/pro-instruments")
@RequiredArgsConstructor
@Tag(name = "PRO Instruments", description = "Standardized patient-reported-outcome instruments (EPDS first)")
public class ProInstrumentController {

    private final ProInstrumentService instrumentService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Instruments that can be administered",
        description = "Active instruments with at least one language of text loaded.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<ProInstrumentSummary>> list() {
        return ResponseEntity.ok(instrumentService.listActive().stream()
            .map(i -> new ProInstrumentSummary(i.getCode(), i.getName(), i.getVersion(),
                instrumentService.languagesOf(i)))
            .filter(s -> !s.languages().isEmpty())
            .toList());
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "An instrument rendered in one language",
        description = "Items and answer options without scores. Falls back to English when the "
            + "requested language is not loaded; the response says which language was served.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProInstrumentViewDTO> get(@PathVariable String code,
                                                    @RequestParam(required = false) String language) {
        return ResponseEntity.ok(instrumentService.render(code, language));
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Load or replace an instrument definition",
        description = "Structure, option scores and per-language text from the validated source, "
            + "with its citation. Replaces items, options and text wholesale.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ProInstrumentViewDTO> importDefinition(@PathVariable String code,
                                                                 @Valid @RequestBody ProInstrumentDefinitionDTO definition) {
        definition.setCode(code);
        return ResponseEntity.ok(instrumentService.importDefinition(definition));
    }

    public record ProInstrumentSummary(String code, String name, String version, List<String> languages) { }
}
