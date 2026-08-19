package com.example.hms.enums;

/**
 * How a hospital's clinical data is isolated from other tenants in the
 * same database (roadmap row 33, v2.0 / Multi-tenancy).
 *
 * <p>The platform's default is {@link #ROW_LEVEL} — every clinical /
 * billing row carries a {@code hospital_id} column and queries are
 * filtered through {@code TenantScopeSpecification}. Hospitals with
 * strong isolation needs (military, foreign-private, regulated
 * jurisdictions) can opt in to {@link #SCHEMA}: their clinical tables
 * live in a dedicated PostgreSQL schema named by
 * {@code Hospital.tenantSchemaName} and Hibernate routes their
 * connections via {@code SchemaTenantConnectionProvider} which sets
 * {@code search_path} per-request.
 *
 * <p>Reference / platform / security tables (e.g. {@code reference.*},
 * {@code platform.*}, {@code security.*}) stay in the shared schema
 * regardless of mode — only the per-tenant clinical surface is split.
 *
 * <p>Switching a live hospital between modes is an operator-driven
 * migration documented in
 * {@code docs/runbooks/schema-per-tenant-migration.md}; the JPA
 * mapping accepts either value but production cutover requires the
 * matching schema to exist before the field flips.
 */
public enum TenantIsolationMode {
    /** Default. Shared schema, row-level filtering on {@code hospital_id}. */
    ROW_LEVEL,

    /** Dedicated PostgreSQL schema; tenant clinical tables physically isolated. */
    SCHEMA
}
