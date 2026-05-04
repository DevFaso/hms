package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.AuditSource;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MVP-8c — common shape across the three audit sources
 * ({@code audit_event_logs}, {@code frontend_audit_events},
 * {@code permission_matrix_audit_events}). Lets the super-admin
 * audit-search UI render a single merged feed.
 *
 * <p>Every field is optional except {@code source}, {@code id},
 * {@code timestamp}, and {@code summary}; the others map best-effort
 * from the underlying entity (e.g. {@code FrontendAuditEvent} has no
 * status, {@code PermissionMatrixAuditEvent} has no hospital).
 */
@Builder
public record AggregatedAuditEventDTO(
    AuditSource source,
    UUID id,
    String eventType,
    String actor,
    UUID hospitalId,
    UUID organizationId,
    String status,
    LocalDateTime timestamp,
    String summary
) { }
