package com.example.hms.controller;

import com.example.hms.payload.dto.panel.PanelAssignmentResponseDTO;
import com.example.hms.payload.dto.panel.PanelOverviewRowDTO;
import com.example.hms.service.panel.PanelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Panel worklists (Tier 2 item 37) — the provider-facing half. Class-level
 * gate for the same fall-open reason as the patient-scoped controller; the
 * admin views tighten further per method.
 */
@RestController
@RequestMapping(value = "/panels", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR',"
    + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
@Tag(name = "Panel Management", description = "Provider / CHW panel worklists.")
@SecurityRequirement(name = "bearerAuth")
public class PanelWorklistController {

    private final PanelService panelService;

    @GetMapping("/my")
    @Operation(summary = "My live panel",
        description = "The ACTIVE empanelments owned by the caller's staff profile at the "
            + "active hospital. 400 when the caller has no staff row there.")
    public ResponseEntity<Page<PanelAssignmentResponseDTO>> myPanel(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(panelService.myPanel(page, size));
    }

    @GetMapping("/providers/{staffId}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "One provider's live panel (admin)",
        description = "Optional role narrows to one panel role — the overview counts are per "
            + "(provider, role), so its drilldown passes the role to match.")
    public ResponseEntity<Page<PanelAssignmentResponseDTO>> providerPanel(
        @PathVariable UUID staffId,
        @RequestParam(required = false) com.example.hms.enums.PanelRole role,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(panelService.providerPanel(staffId, role, page, size));
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Live panel sizes per provider (admin)",
        description = "Every provider/CHW with at least one ACTIVE empanelment, biggest first.")
    public ResponseEntity<List<PanelOverviewRowDTO>> overview() {
        return ResponseEntity.ok(panelService.overview());
    }
}
