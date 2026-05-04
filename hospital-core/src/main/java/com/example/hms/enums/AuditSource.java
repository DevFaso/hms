package com.example.hms.enums;

/**
 * MVP-8c — discriminator for the cross-source audit aggregation.
 * Every {@link com.example.hms.payload.dto.superadmin.AggregatedAuditEventDTO}
 * row carries one of these so the frontend can render source-specific
 * affordances (icons, drill-down links) without parsing the event-type
 * string.
 */
public enum AuditSource {
    /** {@code support.audit_event_logs} — the canonical backend audit trail. */
    SUPPORT,

    /** {@code support.frontend_audit_events} — UI-side telemetry (logins, page nav). */
    FRONTEND,

    /** {@code permission_matrix_audit_events} — permission-matrix promotions/diffs. */
    PERMISSION_MATRIX
}
