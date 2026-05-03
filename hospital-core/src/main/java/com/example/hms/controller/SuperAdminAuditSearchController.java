package com.example.hms.controller;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.superadmin.AuditSearchFilter;
import com.example.hms.payload.dto.superadmin.AuditSearchPageDTO;
import com.example.hms.service.SuperAdminAuditSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MVP-8: Cross-tenant audit search for super admins. All filter params are
 * optional; results are paged and sorted by eventTimestamp desc by default.
 */
@RestController
@RequestMapping("/super-admin/audit-search")
@RequiredArgsConstructor
@Tag(name = "Super Admin Audit Search",
    description = "Cross-tenant audit search across AuditEventLog with impersonator filter (MVP-8 in docs/super-admin-gaps.md)")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminAuditSearchController {

    private final SuperAdminAuditSearchService service;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Cross-tenant search of AuditEventLog with optional filters")
    public ResponseEntity<AuditSearchPageDTO> search(
        @RequestParam(required = false) UUID userId,
        @RequestParam(required = false) String userName,
        @RequestParam(required = false) List<AuditEventType> eventTypes,
        @RequestParam(required = false) AuditStatus status,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) UUID organizationId,
        @RequestParam(required = false) UUID impersonatorUserId,
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) String resourceId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
        Pageable pageable
    ) {
        AuditSearchFilter filter = new AuditSearchFilter(
            userId, userName, eventTypes, status, hospitalId, organizationId,
            impersonatorUserId, entityType, resourceId, fromDate, toDate);
        return ResponseEntity.ok(service.search(filter, pageable));
    }
}
