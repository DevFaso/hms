package com.example.hms.enums;

/**
 * Data-residency region for an Organization (MVP-9 — gap #9 in
 * docs/super-admin-gaps.md).
 *
 * <p>The enum models the *jurisdiction whose data-protection rules apply
 * to this tenant's data*, not the deployment region. A single deployment
 * can host tenants from multiple regions; this label tells the platform
 * which compliance posture to assume for each tenant's audit / export /
 * retention behaviour.
 *
 * <p>Codes are ISO 3166-1 alpha-2 country codes for the West / Central
 * African focus countries currently in scope, plus three umbrella regions
 * for tenants outside the focus list:
 *
 * <ul>
 *   <li>{@code BF} — Burkina Faso (default for legacy rows on V82).
 *   <li>{@code CI} — Côte d'Ivoire.
 *   <li>{@code SN} — Senegal.
 *   <li>{@code GA} — Gabon (CNAMGS).
 *   <li>{@code CM} — Cameroon.
 *   <li>{@code BJ} — Benin.
 *   <li>{@code TG} — Togo.
 *   <li>{@code ML} — Mali.
 *   <li>{@code NE} — Niger.
 *   <li>{@code ML_OAPI} — UEMOA / OAPI multi-state shared rows (umbrella).
 *   <li>{@code EU} — European Union (GDPR).
 *   <li>{@code US} — United States (HIPAA).
 *   <li>{@code OTHER} — anything not yet mapped to a specific code.
 * </ul>
 *
 * <p>Adding a new region is a backwards-compatible change: append to the
 * end of this enum and rerun migrations. The DB column is a free
 * VARCHAR so the application layer can validate and reject unknown
 * codes without a schema change.
 */
public enum OrganizationRegion {
    BF,
    CI,
    SN,
    GA,
    CM,
    BJ,
    TG,
    ML,
    NE,
    ML_OAPI,
    EU,
    US,
    OTHER
}
