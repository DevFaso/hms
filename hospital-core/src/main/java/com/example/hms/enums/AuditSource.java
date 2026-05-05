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
    PERMISSION_MATRIX,

    /**
     * MVP-c3 — platform-configuration writes (feature flags, region
     * policies, release windows, integration credentials). Physically
     * lives in {@code support.audit_event_logs} alongside the other
     * SUPPORT events, but is split out as its own logical source so
     * operators can answer "who changed platform config?" without
     * wading through clinical / billing / security activity. The
     * aggregation service splits each {@code AuditEventLog} row to
     * exactly one of {@code SUPPORT} or {@code PLATFORM_CONFIG} based
     * on its {@code eventType} — no row appears under both.
     */
    PLATFORM_CONFIG
}
