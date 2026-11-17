package com.example.hms.controller;

import com.example.hms.security.audit.WriteAudited;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.AuditEventLogSpecifications;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import com.example.hms.controller.support.AuditEventTypeParams;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.controller.support.StrictQueryParams;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@StrictQueryParams
@WriteAudited(skip = true, reason = "writes here are audit-search housekeeping on the audit log itself")
@RequestMapping("/audit-logs")
@Validated
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "Endpoints for managing system audit logs and tracking events.")
public class AuditEventLogController {

    /**
     * Audit-trail readers (2026-08-23 role audit, C6): platform + hospital
     * administration plus lab leadership — quality managers and lab
     * directors review access trails as part of CAP/ISO 15189 quality
     * audits. Before this gate the controller had NO authorization at all:
     * any authenticated user, patients included, could page the entire
     * audit log.
     *
     * <p>There is no POST here any more (QA D701): it took the actor, the
     * hospital and the event type from the request body, so any signed-in
     * user, a patient included, could write a LOGIN row as a hospital admin or
     * a DATA_DELETE row as a doctor, and hospital admins read them as real.
     * No client called it. Audit rows are written by the server, for the
     * authenticated caller; client telemetry goes to {@code /frontend-audit},
     * whose actor is the principal. AuditWriteSurfaceGuardTest keeps it so.
     */
    private static final String AUDIT_READ_ROLES =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','LAB_DIRECTOR','QUALITY_MANAGER')";
    /** Longest free-text search accepted (QA D794). */
    private static final int MAX_SEARCH_LENGTH = 200;
    /** The same set as {@link #AUDIT_READ_ROLES}, as authorities, for the in-method check. */
    private static final List<String> AUDIT_READER_AUTHORITIES = List.of(
        "ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_ADMIN", "ROLE_LAB_DIRECTOR", "ROLE_QUALITY_MANAGER");

    private final AuditEventLogService auditService;
    private final AuditEventLogRepository auditRepository;
    private final AuditEventLogMapper auditMapper;
    private final ControllerAuthUtils authUtils;
    private final ActingScopeResolver scopeResolver;

    /**
     * The hospitals whose audit rows the caller may read, or {@code null} for
     * the whole platform log (QA D25). Only a verified super-admin in global
     * view reads the platform log; a pinned super-admin reads the hospital
     * pinned; everyone else reads the hospitals they WORK at (a patient seat
     * is not a reason to read a hospital's audit trail), narrowed to the
     * {@code X-Hospital-Id} they named when they named one. The role check in
     * {@code @PreAuthorize} says who may read audit logs at all; this says
     * which rows.
     */
    private Set<UUID> auditScope() {
        ActingScope scope = scopeResolver.current();
        Set<UUID> staff = HospitalContextHolder.getContextOrEmpty().getStaffHospitalIds();
        return switch (scope) {
            case ActingScope.Global global -> null;
            case ActingScope.Pinned pinned -> scopeResolver.isVerifiedSuperAdmin() || staff.contains(pinned.hospitalId())
                ? Set.of(pinned.hospitalId())
                : Set.of();
            // Several hospitals and none named: every hospital they work at.
            case ActingScope.Refused(ActingScope.Reason reason) when reason == ActingScope.Reason.AMBIGUOUS -> staff;
            case ActingScope.Refused refused -> Set.of();
            case ActingScope.PatientOwned owned -> Set.of();
        };
    }

    private Page<AuditEventLogResponseDTO> scopedPage(Set<UUID> scope, Specification<AuditEventLog> filter,
                                                      Pageable pageable) {
        return auditRepository.findAll(AuditEventLogSpecifications.anchoredAt(scope).and(filter), newestFirst(pageable))
            .map(auditMapper::toDto);
    }

    private static Pageable newestFirst(Pageable pageable) {
        return pageable.getSort().isSorted()
            ? pageable
            : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "eventTimestamp"));
    }

    @GetMapping
    @PreAuthorize(AUDIT_READ_ROLES)
    @Operation(summary = "Get All Audit Logs",
        description = "Retrieve the audit logs the caller may read, paginated. Optionally filtered by date range, "
            + "event types and a free-text search, all applied by the server over every row (QA D794). "
            + "eventType and eventTypes are the same filter (repeated or comma-separated; a row matches any). "
            + "An unknown parameter or event type is a 400, never ignored (QA D801).")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully")
    @ApiResponse(responseCode = "400", description = "Unknown event type or unknown parameter")
    public ResponseEntity<Page<AuditEventLogResponseDTO>> getAllAuditLogs(
        @Parameter(description = "Start of date range (ISO 8601), e.g. 2024-01-01T00:00:00")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
        @Parameter(description = "End of date range (ISO 8601), e.g. 2024-12-31T23:59:59")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
        @Parameter(description = "Only these event types, e.g. USER_CREATE (repeated or comma-separated)")
        @RequestParam(required = false) List<String> eventType,
        @Parameter(description = "Same filter as eventType; both may be given and a row matches any value")
        @RequestParam(required = false) List<String> eventTypes,
        @Parameter(description = "Free text matched against the actor, description, entity type and resource name")
        @RequestParam(required = false) @Size(max = MAX_SEARCH_LENGTH) String search,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        Set<AuditEventType> types = AuditEventTypeParams.parse(eventType, eventTypes);
        Specification<AuditEventLog> filter = AuditEventLogSpecifications.between(fromDate, toDate)
            .and(AuditEventLogSpecifications.ofTypes(types))
            .and(AuditEventLogSpecifications.matching(search));
        Set<UUID> scope = auditScope();
        if (scope != null) {
            return ResponseEntity.ok(scopedPage(scope, filter, pageable));
        }
        if (!types.isEmpty() || (search != null && !search.isBlank())) {
            return ResponseEntity.ok(auditRepository.findAll(
                    AuditEventLogSpecifications.everyRow().and(filter), newestFirst(pageable))
                .map(auditMapper::toDto));
        }
        if (fromDate != null || toDate != null) {
            return ResponseEntity.ok(auditService.getAuditLogsByDateRange(fromDate, toDate, pageable));
        }
        Page<AuditEventLog> page = auditRepository.findAll(pageable);
        return ResponseEntity.ok(page.map(auditMapper::toDto));
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize(AUDIT_READ_ROLES)
    @Operation(summary = "Get Audit Logs by Hospital", description = "Retrieve audit logs scoped to a specific hospital, ordered by timestamp descending.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully")
    public ResponseEntity<Page<AuditEventLogResponseDTO>> getLogsByHospital(
        @Parameter(description = "UUID of the hospital", required = true)
        @PathVariable UUID hospitalId,
        @PageableDefault(size = 20) Pageable pageable) {
        Set<UUID> scope = auditScope();
        if (scope != null && !scope.contains(hospitalId)) {
            throw new AccessDeniedException("You may only read the audit log of a hospital you work at.");
        }
        return ResponseEntity.ok(auditService.getAuditLogsByHospital(hospitalId, pageable));
    }

    /**
     * Audit readers may look at anyone; everyone else may look at themselves. The
     * profile's Activity tab asks for the caller's own id, and for a patient the
     * old rule answered 403, which the portal turns into the full-page Access
     * Denied screen (dev, 2026-09-13).
     */
    @GetMapping("/user/{userId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get Audit Logs by User",
        description = "Retrieve audit logs for a specific user based on their UUID. Audit readers may name any user; other callers only themselves.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully")
    public ResponseEntity<Page<AuditEventLogResponseDTO>> getLogsByUser(
        @Parameter(description = "UUID of the user", required = true)
        @PathVariable UUID userId,
        @PageableDefault(size = 20) Pageable pageable,
        Authentication auth) {
        boolean self = requireSelfUnlessAuditReader(userId, auth);
        Set<UUID> scope = self ? null : auditScope();
        if (scope != null) {
            return ResponseEntity.ok(scopedPage(scope, AuditEventLogSpecifications.byUser(userId), pageable));
        }
        return ResponseEntity.ok(auditService.getAuditLogsByUser(userId, pageable));
    }

    /**
     * True when the caller reads their own activity (unscoped: it is theirs);
     * false for an audit reader reading someone else (scoped to the reader's
     * hospitals by the caller); anyone else naming another user is refused.
     */
    private boolean requireSelfUnlessAuditReader(UUID userId, Authentication auth) {
        UUID self = authUtils.resolveUserId(auth).orElse(null);
        if (self != null && self.equals(userId)) {
            return true;
        }
        boolean reader = AUDIT_READER_AUTHORITIES.stream().anyMatch(a -> authUtils.hasAuthority(auth, a));
        if (!reader) {
            throw new AccessDeniedException("Only audit readers may view another user's activity.");
        }
        return false;
    }

    @GetMapping("/event-types-summary")
    @PreAuthorize(AUDIT_READ_ROLES)
    @Operation(summary = "Get event type counts", description = "Retrieve a summary of audit event counts grouped by event type.")
    @ApiResponse(responseCode = "200", description = "Event type summary retrieved successfully")
    public ResponseEntity<List<Map<String, Object>>> getEventTypesSummary() {
        Set<UUID> scope = auditScope();
        List<Object[]> rows;
        if (scope == null) {
            rows = auditRepository.countByEventType();
        } else if (scope.isEmpty()) {
            rows = List.of();
        } else {
            rows = auditRepository.countByEventTypeForHospitals(scope);
        }
        List<Map<String, Object>> summary = rows.stream()
            .map(row -> Map.<String, Object>of(
                "eventType", row[0] != null ? row[0].toString() : "UNKNOWN",
                "count", row[1]
            ))
            .toList();
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/event-type-status")
    @PreAuthorize(AUDIT_READ_ROLES)
    @Operation(summary = "Get Audit Logs by Event Type (with optional status)",
        description = "Retrieve audit logs filtered by event types and optionally by status. eventType and "
            + "eventTypes are the same filter (repeated or comma-separated); at least one value is required.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully")
    @ApiResponse(responseCode = "400", description = "Missing or invalid audit event type, invalid status, unknown parameter")
    public ResponseEntity<Page<AuditEventLogResponseDTO>> getLogsByEventTypeAndStatus(
        @RequestParam(required = false) List<String> eventType,
        @RequestParam(required = false) List<String> eventTypes,
        @RequestParam(required = false) String status,
        @PageableDefault(size = 20) Pageable pageable) {

        Set<AuditEventType> types = AuditEventTypeParams.parse(eventType, eventTypes);
        if (types.isEmpty()) {
            // This endpoint IS the event-type filter: none named is not "every row".
            throw new BusinessException("audit.eventType.required");
        }
        AuditStatus parsedStatus = parseStatus(status);
        Specification<AuditEventLog> filter = AuditEventLogSpecifications.ofTypes(types, parsedStatus);
        Set<UUID> scope = auditScope();
        if (scope != null) {
            return ResponseEntity.ok(scopedPage(scope, filter, pageable));
        }
        return ResponseEntity.ok(auditRepository.findAll(
                AuditEventLogSpecifications.everyRow().and(filter), newestFirst(pageable))
            .map(auditMapper::toDto));
    }

    /** A blank status is no filter; an unknown one is the caller's error, in their language. */
    private static AuditStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AuditStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String value = status.trim();
            throw new BusinessException("audit.status.invalid",
                value.length() <= 64 ? value : value.substring(0, 64) + "…");
        }
    }

    @GetMapping("/target")
    @PreAuthorize(AUDIT_READ_ROLES)
    @Operation(summary = "Get Audit Logs by Target Resource", description = "Retrieve audit logs based on the affected entity type and resource ID.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully")
    public ResponseEntity<Page<AuditEventLogResponseDTO>> getLogsByTarget(
        @RequestParam String entityType,
        @RequestParam String resourceId,
        @PageableDefault(size = 20) Pageable pageable) {
        Set<UUID> scope = auditScope();
        if (scope != null) {
            return ResponseEntity.ok(scopedPage(scope,
                AuditEventLogSpecifications.byTarget(entityType, resourceId), pageable));
        }
        return ResponseEntity.ok(
            auditRepository.findByEntityTypeIgnoreCaseAndResourceId(entityType, resourceId, pageable)
                .map(auditMapper::toDto)
        );
    }
}
