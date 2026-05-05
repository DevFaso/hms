package com.example.hms.controller;

import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.payload.dto.superadmin.IntegrationMessageEventDTO;
import com.example.hms.payload.dto.superadmin.IntegrationMessagePageDTO;
import com.example.hms.service.SuperAdminIntegrationMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MVP-c3 — Bridges-style search + replay surface for the per-message
 * log. Powers the operator's "where did claim X go?" / "replay this
 * failed ADT" workflow, which goes live the moment a real partner
 * connector replaces the stubs.
 */
@RestController
@RequestMapping("/super-admin/integration-messages")
@RequiredArgsConstructor
@Tag(name = "Super Admin — Integration Messages",
    description = "Bridges-style per-message log + DLQ + replay (MVP-c3 Tier 2 #5).")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminIntegrationMessageController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    /** Aligned with SuperAdminAuditAggregationServiceImpl.MAX_PAGE_SIZE. */
    private static final int MAX_PAGE_SIZE = 5_000;

    private final SuperAdminIntegrationMessageService service;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Search the message log; every filter is optional.")
    public ResponseEntity<IntegrationMessagePageDTO> search(
        @RequestParam(value = "integrationId", required = false) String integrationId,
        @RequestParam(value = "organizationId", required = false) UUID organizationId,
        @RequestParam(value = "status", required = false) IntegrationMessageStatus status,
        @RequestParam(value = "fromDate", required = false) LocalDateTime fromDate,
        @RequestParam(value = "toDate", required = false) LocalDateTime toDate,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return ResponseEntity.ok(service.search(
            integrationId, organizationId, status, fromDate, toDate,
            PageRequest.of(safePage, safeSize)));
    }

    @PostMapping("/{messageId}/replay")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Replay a previously-FAILED message; reuses the original correlation id.")
    public ResponseEntity<IntegrationMessageEventDTO> replay(@PathVariable UUID messageId) {
        return ResponseEntity.ok(service.replay(messageId));
    }
}
