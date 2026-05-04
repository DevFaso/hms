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
 * <p>Only {@code source} and {@code id} are guaranteed non-null. All
 * other fields — including {@code timestamp} and {@code summary} — are
 * mapped best-effort from the underlying entity and may be {@code null}
 * depending on the source row (e.g. {@code FrontendAuditEvent} has no
 * status, {@code PermissionMatrixAuditEvent} has no hospital, and a
 * partially-populated row may have a null {@code createdAt} /
 * description).
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
