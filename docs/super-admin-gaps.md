# Super-Admin Role: Capabilities, Gaps & MVP Roadmap

> Audit date: 2026-05-02 (last updated 2026-05-04) · Baseline: `main`
> @ `b9f3fe0b` (**MVPs 1–9 + sub-MVPs 4b/5b/6b/7b/8b/9b + MVP-c batch
> backend all in production**) · `uat` @ `ec2d4a5e` · `develop` @
> `050d4d94` (MVP-c2 backend + MVP-c2 frontend merged — closes the
> two MVP-c2 backend gaps and ships the four MVP-c frontend surfaces;
> awaiting `develop → uat → main` promote chain).
> Branches:
> `feature/super-admin-gaps` (MVP-1 + MVP-2 — shipped to main `006384fc`),
> `feature/super-admin-gaps-mvp3-integration-health` (MVP-3 — PR #223 →
> develop `b280a0bd` → main `34cd0c56`),
> `feature/super-admin-gaps-mvp4-support-impersonation` (MVP-4 — PR #224
> → develop `bbf09844` → main `34cd0c56`; six Copilot security findings
> folded in `db49e73e`),
> `feature/super-admin-gaps-mvp5-surface-consolidation` (MVP-5 — PR #225
> → develop `f7fbfc9d` → main `34cd0c56`; two Copilot review findings
> folded in `c03bd80a`),
> `feature/super-admin-gaps-mvp6-7-8-trio` (MVP-6 + MVP-7 + MVP-8 —
> PR #228 → develop `9c06ef22` → main `6530997f`; six fixup commits
> covering CI build break, 12 Copilot review findings including 2
> critical security, 19 cascading test-context failures, SonarCloud
> coverage 0.3% → 94.3%, and 16 SonarCloud code-smell findings),
> `feature/super-admin-gaps-doc-refresh` (MVP-9 + MVP-{6,7,8} batch
> follow-ups — PR #231 → develop `6da6ea5b`, PR #232 → uat `28e39145`,
> PR #233 → main `5ac9fbd2`; bundles MVP-9 Data-Residency with the
> Subscriptions "New plan" defect fix and the FR/ES i18n backfill the
> MVP-{6,7,8} batch shipped without; +12 Karma specs (802 → 814), +5
> backend service tests + 3 controller IT specs, all 13 CI checks green
> including the JaCoCo 80% INSTRUCTION gate),
> `feature/super-admin-gaps-mvp-4b-5b-6b` (MVP-4b impersonation TTL
> countdown + auto-stop + MVP-5b super-admin URL namespace consolidation
> + MVP-6b plan-tier feature enforcement — PR #234 → develop `85b4bc43`,
> PR #235 → uat `86e6696f`, PR #236 → main `661ac59a`; +9 Karma specs
> (814 → 823), +9 backend service-layer specs, JaCoCo 80% gate green),
> `feature/super-admin-gaps-mvp-7b-8b-9b` (MVP-7b per-tenant
> feature-flag overrides + MFA per-hospital filter + STOMP broadcast
> banner consumer + MVP-8b audit-search CSV export + per-operator
> saved-search localStorage + MVP-9b region-aware audit-search filter —
> PR #237 → develop `c4c9feb9`, PR #238 → uat `ce18498e`, PR #239 →
> main `67406bd3`; V83 migration adds `organization_id` to
> `platform_feature_flag_overrides` with composite UNIQUE; +13 Karma
> specs (823 → 836), +12 backend service specs, JaCoCo 80% gate green
> in 7m 56s),
> `feature/super-admin-gaps-mvp-c-batch` (MVP-c roll-up — promoted via
> direct merge develop `273244a3` → uat `ec2d4a5e` → main `b9f3fe0b`,
> 10 commits including a Copilot-review-fix follow-up. **Backend
> shipped:** MVP-2c GDPR packager + AES-256-GCM purge archive
> envelope, MVP-3b connector SPI + `/probe` + `/resync` + `/history`
> endpoints + stub NHIS/NHIA/CNAMGS/mutuelle providers, MVP-5c nginx
> 301s for `/feature-flags` + `/analytics`, MVP-6c jsonb
> `feature_keys` + plan-tier audit emission with bounded dedup,
> MVP-8c saved-search REST CRUD, MVP-9c per-region retention +
> export-format policy backend wired through `TenantPurgeExecutor` +
> `TenantExportPackager`, hospital-level lifecycle state machine +
> JWT login-block + MFA step-up. Migrations on disk: **V84**
> hospital_lifecycle, **V85** subscription_plan_feature_keys_jsonb,
> **V86** region_policy, **V87** audit_saved_search, **V88**
> integration_health_event — all additive. The two backend gaps
> from the original scope and the four frontend surfaces are
> handled by the two MVP-c2 follow-up branches below.),
> `feature/super-admin-mvp-c2-backend` (closes the two MVP-c batch
> backend gaps — direct-merged into develop `b2089a8a`, PR #241
> closed as superseded. 2 commits + Copilot review fixup.
> **Shipped:** MVP-8c cross-source audit aggregation
> (`AuditSource` enum, `AggregatedAuditEventDTO`,
> `SuperAdminAuditAggregationServiceImpl` unioning all three audit
> sources, `GET /super-admin/audit-search/aggregated`); MVP-9c
> region-routing scaffold (`RegionRoutingResolver`,
> `TenantProvisioningClient` interface, `StubTenantProvisioningClient`
> with HTTP 501 strict mode, wired into
> `SuperAdminOrganizationProvisioningServiceImpl`). 17 new backend
> tests; coverage on every new class ≥91.7% branch / ≥96.9%
> instruction.),
> `feature/super-admin-mvp-c2-frontend` (wires the four MVP-c
> frontend surfaces — direct-merged into develop `050d4d94`,
> PR #242 closed as superseded. 2 commits + Copilot review fixup.
> **Shipped:** MVP-3b probe / resync row buttons + 24h history
> sparkline drawer; new `/hospitals/:id` detail page with Lifecycle
> panel (state chip, history meta, state-aware action buttons +
> dialogs); `/super-admin/data-residency/policy` editor for
> per-region retention / export-format / deployment URL;
> audit-search aggregation tab + source-toggle checkboxes (last
> selection locked) + saved-search localStorage → REST migration
> shim. Small additive backend change exposed
> `HospitalResponseDTO.lifecycleState` so the list chip renders
> without an N+1 lookup. Karma 865/865 specs green; full
> `:hospital-core:test` + 80% INSTRUCTION gate green.).

## Executive Summary

The super admin is the highest-privilege role in HMS — operating across organizations and hospitals to manage tenants, security policy, feature flags, user governance, and platform health. The current implementation has a **strong backend surface** (8 dedicated `SuperAdmin*` controllers, multi-tenant scoping via `Organization → Hospital`, feature-flag overrides per tenant, security-policy baselines, credential lifecycle, platform registry).

This document originally captured 9 numbered gaps (MVP-1 through
MVP-9) plus a sub-MVP series (MVP-4b through MVP-9b) plus an MVP-c
roll-up batch plus an MVP-c2 follow-up. **All headline MVPs and
sub-MVPs are in production on `main`. The MVP-c batch backend is
on `main`; the MVP-c2 backend (two gaps) and MVP-c2 frontend (four
surfaces) are merged into `develop` and awaiting the
`develop → uat → main` promote chain.** This doc is pruned to the
live work — completed sections are removed, see git history for the
historical record.

## Current Capabilities (what super admin can already do today)

### Backend — `hospital-core/`

8 dedicated controllers under `com.example.hms.controller`:

| Controller | Surface |
| --- | --- |
| `SuperAdminOrganizationController` | `/super-admin/organizations` — summary, hierarchy, create org, assign/unassign hospitals |
| `SuperAdminDashboardController` | `/super-admin/{summary,appointments,patients,encounters,staff-availability,patient-consents,analytics}` |
| `SuperAdminUserGovernanceController` | `/super-admin/users` — create, bulk CSV import, force password reset, password-rotation status |
| `SuperAdminSecurityPolicyController` | `/super-admin/security` — policy baselines, pending approvals, baseline export |
| `SuperAdminSecurityRuleController` | `/super-admin/security/rules` — rule sets, templates, simulation |
| `SuperAdminPlatformRegistryController` | `/super-admin/platform` — registry summary, release windows, snapshot export |
| `SuperAdminCredentialLifecycleController` | `/super-admin/credentials` — credential health, MFA, recovery contacts |
| `SuperAdminLabOrderController` | `/super-admin/lab-orders` — name-based lab order creation (no UUID exposure) |

Cross-cutting:

- `OrganizationSecurityController` — `/organizations/{id}/security` (compliance, policies, rules) gated to `ROLE_SUPER_ADMIN` / `ROLE_HOSPITAL_ADMIN`.
- `FeatureFlagController` — `/feature-flags` PUT/DELETE gated to `ROLE_SUPER_ADMIN`; `FeatureFlagOverride` model supports per-tenant overrides.
- `PermissionMatrixController` — snapshot publishing, audit recording (super-admin only).
- `RateLimitFilter` — request rate limiting at the security-filter layer.
- Multi-tenancy: `Organization → Hospital → Staff/Patient` with `TenantScopeSpecification` and JWT claims `CLAIM_IS_SUPER_ADMIN`, `CLAIM_PERMITTED_ORGANIZATION_IDS`, `CLAIM_PERMITTED_HOSPITAL_IDS`.

### Frontend — `hospital-portal/src/app/`

Super-admin-reachable routes in `app.routes.ts`:

| Route | Roles | Component |
| --- | --- | --- |
| `/feature-flags` | SUPER_ADMIN only | `feature-flags/feature-flags` |
| `/analytics` | SUPER_ADMIN only | `analytics/analytics` |
| `/organizations` | ADMIN + SUPER_ADMIN | `organizations/organization-list` |
| `/users` | ADMIN + SUPER_ADMIN | `users/user-list` |
| `/roles` | ADMIN + SUPER_ADMIN | `roles/role-list` |
| `/platform` | ADMIN + SUPER_ADMIN | `platform/platform` |
| `/admin` | ADMIN + SUPER_ADMIN | `admin/admin` (calls `/super-admin/summary`) |
| `/hospitals` | SUPER_ADMIN + others | `hospitals/hospital-list` |
| `/audit-logs` | HOSPITAL_ADMIN, ADMIN, SUPER_ADMIN | `audit-logs/audit-logs` |
| `/admin/order-sets` | HOSPITAL_ADMIN, SUPER_ADMIN | order-set authoring |
| `/admin/integrations/dhis2` | HOSPITAL_ADMIN, SUPER_ADMIN | DHIS2 ADX administration |

Role check primitive: `RoleContextService.isSuperAdmin` computed signal.

## Gap Analysis: HMS vs. Multi-Hospital SaaS Standard

| # | Function | HMS State | Comments / Gap |
| --- | --- | --- | --- |
| 1 | **Unified super-admin landing page** | None | Capabilities scattered across ~10 top-level routes. `admin.ts` is shared with `ROLE_ADMIN` and surfaces hospital-admin tiles. Super admin has no mission-control. **Highest leverage / lowest effort — pure UI consolidation.** |
| 2 | **Tenant lifecycle (suspend / archive / restore / purge)** | Partial — create + assign/unassign only | No org-wide login block, no archive, no GDPR export-then-purge. **Required before serious multi-tenant onboarding.** |
| 3 | **Partner-connector / integration health console** | None | `EligibilityProvider` SPI + `PlatformIntegrationDescriptor` + `BillingIntegrationAdapter` / `EhrIntegrationAdapter` exist; no UI showing per-org connector inventory, last sync, failure rate. Pairs naturally with the open partner-API (NHIS / NHIA / CNAMGS / mutuelle) item. |
| 4 | **Support impersonation ("act as user") with audit** | None | Most-requested support tool for multi-hospital SaaS. Must be MFA-stepped-up and permanently audited. |
| 5 | **Subscription / per-org plan / seat quotas** | Feature-flag overrides exist but not tier-driven | No `SubscriptionPlan` entity, no seat counts, no plan-based feature gating. Defer until first paid tenant is real. |
| 6 | **Emergency global controls** | None | Force-logout-all, kill-switch a feature globally, force MFA re-enrolment, broadcast banner. |
| 7 | **Cross-tenant audit search UI** | Backend present, no UI | `AuditEventLog`, `FrontendAuditEvent`, `PermissionMatrixAuditEvent` exist; super admin has no unified search/filter UI spanning them. |
| 8 | **Data-residency / region tagging on Organization** | None | Cross-border (BF / SN / CI) compliance — schema-level decision worth flagging early. |
| 9 | **Super-admin surface duplication** | Three landing pages live side-by-side: `/dashboard` still renders a `'superadmin'` view branch (stat strip + quick actions + recent audit), `/admin` is still in the side-nav for ADMIN+SUPER_ADMIN, and `/super-admin` (the Control Tower from MVP-1) is the intended single home. *Identified after MVP-4 review* — MVP-1 added the Control Tower without retiring the duplicates. |

## MVP List (Priority Order)

1. **Super-Admin Control Tower** — landing page · *shipped to main `006384fc`*
2. **Tenant Lifecycle** — suspend / archive / restore / purge · *shipped to main `006384fc`*
3. **Partner-Connector / Integration Health Console** · *PR #223 → develop `b280a0bd` → main `34cd0c56`*
4. **Support Impersonation with Audit** · *PR #224 → develop `bbf09844` → main `34cd0c56` (six Copilot security findings folded in `db49e73e`)*
5. **Super-Admin Surface Consolidation** · *PR #225 → develop `f7fbfc9d` → main `34cd0c56` (two Copilot review findings folded in `c03bd80a`)*
6. **Subscription / Plan / Quotas** · *PR #228 → develop `9c06ef22` → main `6530997f`*
7. **Emergency Global Controls** · *PR #228 → develop `9c06ef22` → main `6530997f`*
8. **Cross-Tenant Audit Search UI** · *PR #228 → develop `9c06ef22` → main `6530997f`*
9. **Data-Residency / Region Tagging** · *PR #231 → develop `6da6ea5b` → PR #232 → uat `28e39145` → PR #233 → main `5ac9fbd2`*

---

## Risks & Open Questions

No live risks remain on `main` itself — the items previously listed
here (hospital-lifecycle UI; region-routing scope creep) shipped on
`develop` `050d4d94` via the MVP-c2 branches and are awaiting the
promote chain. The remaining caveats are about *runtime behaviour*
of shipped code (still listed in [Caveats](#caveats) below):

- **MVP-3b partner protocols ship as stubs.** `CnamgsConnector` /
  `MutuelleConnector` / `NhiaConnector` / `NhisConnector` extend
  `StubPartnerConnector` and return synthetic success on probe /
  resync. Real wire protocols (HL7 / FHIR / proprietary REST) drop
  into the same SPI once partner specs + sandbox credentials land.
- **MVP-9c remote provisioning is strict-mode by default.** Setting
  `region_policy.target_deployment_url` while the only registered
  `TenantProvisioningClient` is `StubTenantProvisioningClient`
  causes tenant creation to throw HTTP 501. This is intentional
  (silent local fallback is a data-residency violation for a
  GDPR-tagged region) but operators must be told before they wire
  the column.
- **MVP-2c KEK source defaults to `noop` in dev.** Production must
  export `HMS_TENANT_ARCHIVE_KEK` (base64) until a real KMS
  integration replaces the env-source. The abstraction
  (`hms.tenant-archive.kek-source`) is in place so swapping the
  source is a one-bean change.

## References

- `hospital-core/src/main/java/com/example/hms/config/SecurityConstants.java` — role constants, JWT claims.
- `hospital-core/src/main/java/com/example/hms/controller/SuperAdmin*.java` — 8 controllers.
- `hospital-portal/src/app/app.routes.ts` — route + role guard map.
- `hospital-portal/src/app/core/role-context.service.ts` — `isSuperAdmin` signal.
- `docs/gap.md` — Lab Director gap analysis (prior precedent for this document's structure).

---

## MVP-c batch + MVP-c2 follow-ups (status by environment)

Per the "all in one branch" directive on 2026-05-03, the seven
deferred items from MVP-1 through MVP-9 rolled up into the
`feature/super-admin-gaps-mvp-c-batch` branch (10 commits) and
shipped on 2026-05-03 — backend only — through develop `273244a3`
→ uat `ec2d4a5e` → main `b9f3fe0b`. The deep-verification of that
batch found two backend gaps (MVP-8c cross-source aggregation,
MVP-9c routing scaffold) and four frontend gaps (MVP-3b
probe/resync/history UI, hospital-lifecycle UI, MVP-9c policy
editor UI, MVP-8c aggregation tab). Those six gaps were closed via
two follow-up branches:

- **`feature/super-admin-mvp-c2-backend`** — direct-merged into
  develop `b2089a8a` on 2026-05-03 (PR #241 closed as superseded).
- **`feature/super-admin-mvp-c2-frontend`** — direct-merged into
  develop `050d4d94` on 2026-05-04 (PR #242 closed as superseded).

Both branches are on `develop` only — `uat` and `main` still hold
the MVP-c-batch state. The next promote step is `develop → uat`,
then `uat → main`, on the same direct-merge pattern as the prior
batches.

### Closure status by environment

**Migration-number correction (informational).** V84
hospital_lifecycle, V85 subscription_plan_feature_keys_jsonb, V86
region_policy, V87 audit_saved_search, V88 integration_health_event.

| Item | On `main` `b9f3fe0b` | On `develop` `050d4d94` |
| --- | --- | --- |
| MVP-3b probe / resync / history endpoints | ✅ backend | ✅ backend |
| MVP-3b probe/resync/history UI | ❌ | ✅ row buttons + lazy-loaded 24h sparkline drawer |
| MVP-8c saved-search REST CRUD | ✅ backend | ✅ backend |
| MVP-8c saved-search FE migration | ❌ (localStorage only) | ✅ REST + idempotent localStorage→REST shim with per-upload `catchError` |
| MVP-8c cross-source aggregation | ❌ | ✅ `AuditSource` + `AggregatedAuditEventDTO` + `SuperAdminAuditAggregationServiceImpl` + `GET /audit-search/aggregated`; aggregation tab on the audit-search page with source toggles (last selection locked) |
| MVP-9c per-region retention + export-format policy | ✅ backend | ✅ backend + `/super-admin/data-residency/policy` editor |
| MVP-9c region-routing scaffold | ❌ | ✅ `RegionRoutingResolver` + `TenantProvisioningClient` + `StubTenantProvisioningClient` (HTTP 501 strict mode) wired into provisioning |
| Hospital-level lifecycle state machine | ✅ backend (V84 + service + JWT login-block + MFA step-up) | ✅ backend + `/hospitals/:id` detail page with Lifecycle panel + state chip on hospital list (gated to `ROLE_SUPER_ADMIN`) |

The detailed per-item scope descriptions below remain valid as the
*intended* contract; the table above is authoritative for what is
actually on each environment.

### MVP-3b: Test connection + Re-sync + per-integration time-series + connector framework

Originally deferred from MVP-3 (line 485 of this doc).

- **Test connection action.** New `IntegrationConnectivityProbe`
  contract on `PlatformIntegrationAdapter` — `Probe probe()`. Each
  adapter returns `Probe.ok(latencyMs)` / `Probe.failed(message)`.
  `POST /super-admin/integrations/{id}/probe` calls it, records the
  outcome through the existing `IntegrationHealthRecorder`, and
  returns the result. ROLE_SUPER_ADMIN.
- **Re-sync action.** `POST /super-admin/integrations/{id}/resync`
  invokes the adapter's `Resyncable.resync(orgId)` if the adapter
  implements it. Async via `@Async` so the UI returns immediately;
  the recorder captures success / failure as the work completes.
  Adapters that don't implement `Resyncable` return 422.
- **Per-integration time-series history.** New table
  `clinical.integration_health_event` (V84, additive) records every
  recorder call with `(integration_id, organization_id, status,
  latency_ms, error_message, recorded_at)`. New
  `GET /super-admin/integrations/{id}/history?windowHours=24` returns
  bucketed counts (`HEALTHY` / `DEGRADED` / `FAILING` per hour bucket).
- **Connector framework.** New `PartnerConnector` SPI with stub
  implementations under
  `com.example.hms.integration.partner.{nhis,nhia,cnamgs,mutuelle}`.
  Each stub returns `Probe.failed("Connector in stub mode — partner
  protocol not yet wired")` and a no-op resync that emits a
  `Probe.ok` after a configurable delay so the UI exercise path is
  testable. Real partner protocols (HL7 / FHIR / proprietary REST)
  drop into the same SPI when specs land.
- **Frontend.** Two action buttons on each integration row of the
  Integration Health Console (Test connection / Re-sync), plus a
  **History** drawer that renders a 24 h sparkline of the bucketed
  counts. `IntegrationHealthService` gains the three new endpoints.

### MVP-8c: Cross-source audit aggregation + persisted/shared saved searches

Originally deferred from MVP-8b (line 1236 of this doc).

- **Cross-source aggregation.** `AggregatedAuditEvent` projection
  unions rows from `support.audit_event_logs`,
  `frontend_audit_event`, and `permission_matrix_audit_event` into a
  common shape (id, source, eventType, actor, organizationId,
  hospitalId, status, timestamp, summary). New
  `GET /super-admin/audit-search/aggregated` accepts the existing
  `AuditSearchFilter` plus a `sources` array (default: all three).
  Service materialises each source query separately, merges with a
  bounded heap-merge sort on timestamp DESC, and paginates the
  merged stream. Backend-side guard rails: per-source LIMIT to
  prevent runaway queries; explicit `EXPLAIN`-friendly indexes
  on the new sources where missing.
- **Server-side saved searches.** New `audit_saved_search` table
  (V87, additive) stores `(id, owner_user_id, name, filter_json,
  shared bool, created_at, updated_at)`. New
  `/super-admin/audit-search/saved` REST CRUD + `?include=shared`
  to list the operator's own searches plus searches another super
  admin marked `shared=true`. Frontend `AuditSavedSearchService`
  switches from localStorage to REST; `localStorage` legacy entries
  are migrated on first load (one-shot upload, then cleared).
- **Sharing.** Toggle on the saved-search row flips
  `shared` so other super admins see it on their list. Owner's
  username surfaced on the row so an operator can attribute the
  shared search.

### MVP-9c: Per-region retention overrides + per-region export-format defaults + region routing config

Originally deferred from MVP-9b (line 1260 of this doc). The two
*overrides* ship as real code; the *routing* piece ships as
config-driven scaffolding (a `RegionRoutingResolver` consulted at org
provisioning time) — physical multi-region Railway deployments stay
an ops task.

- **V88 migration (additive).** New `platform.region_policy` table
  keyed on `(region)` with columns `retention_days INTEGER NULL`,
  `default_export_format VARCHAR(32) NULL`,
  `target_deployment_url VARCHAR(255) NULL`,
  `updated_at TIMESTAMPTZ NOT NULL`,
  `updated_by VARCHAR(255) NOT NULL`. Seed rows for every
  `OrganizationRegion` enum value with NULL overrides.
- **Retention.** `TenantPurgeJob` and existing retention sweeps
  consult `RegionPolicyService.resolveRetentionDays(orgRegion)` —
  null falls back to the global policy, non-null overrides it.
- **Export format.** GDPR-tagged regions (EU + any region opting in
  via the new column) get `default_export_format = 'GDPR_PORTABILITY'`
  in the seed; the `TenantExportPackager` (MVP-2c above) reads the
  region policy first, falling back to the global default.
- **Routing.** `RegionRoutingResolver` reads
  `target_deployment_url` and is called by
  `TenantProvisioningService` at create time. When set + non-empty,
  the new tenant is created on the remote deployment via a
  configured tenant-provisioning REST hook (today: stubbed; the
  hook surface is `TenantProvisioningClient` so a real impl can be
  swapped in). When unset, provisioning runs on the local
  deployment as today.
- **Frontend.** New `/super-admin/data-residency/policy` view —
  per-region table with editable retention / export-format /
  deployment-URL columns. ROLE_SUPER_ADMIN.

### Hospital-level lifecycle state machine

Originally deferred from MVP-2 (line 1028 of this doc) as a Risks &
Open Questions item.

- **V84-companion migration.** `Hospital.lifecycle_status`
  (`ACTIVE` / `SUSPENDED` / `ARCHIVED` / `PURGE_SCHEDULED` /
  `PURGED`), `suspended_at`, `archived_at`, `purge_scheduled_for` —
  mirrors the `Organization` columns from V77.
- **`HospitalLifecycleService`.** Same state-machine semantics as
  `OrganizationLifecycleService` (transition validation, audit
  emission, purge scheduling). Suspending an *organization*
  cascades implicit suspension to every Hospital under it for login-
  block purposes; lifting the org back to `ACTIVE` does **not**
  auto-resume hospitals that were suspended independently — those
  must be restored explicitly so a partial off-boarding stays
  partial.
- **`JwtAuthenticationFilter`** rejects tokens whose
  `permittedHospitalIds` contains a `SUSPENDED`/`ARCHIVED` hospital
  (when not super admin). Super admin retains visibility.
- **Endpoints.** Mirror the org endpoints under
  `/super-admin/hospitals/{id}/{suspend,restore,archive,schedule-
  purge,cancel-purge,lifecycle}`.
- **Frontend.** Hospital detail view gains a Lifecycle panel +
  action buttons gated on SUPER_ADMIN; hospital list shows a state
  chip mirroring the org-list pattern.

<a id="caveats"></a>

### Caveats

- **MVP-3b partner protocols are stubs.** `CnamgsConnector`,
  `MutuelleConnector`, `NhiaConnector`, `NhisConnector` extend
  `StubPartnerConnector` and return synthetic success on probe /
  resync. Real wire protocols drop in via the same SPI once partner
  specs + sandbox credentials land.
- **MVP-9c remote provisioning is strict-mode by default.** Setting
  `region_policy.target_deployment_url` while the only registered
  `TenantProvisioningClient` is `StubTenantProvisioningClient`
  causes tenant creation to throw HTTP 501. Intentional — silent
  local fallback is a data-residency violation for a GDPR-tagged
  region — but operators must register a real client before turning
  the column on.
- **MVP-2c KEK source** defaults to `noop` in dev profiles.
  Production must export `HMS_TENANT_ARCHIVE_KEK` (base64) until a
  real KMS integration replaces the env-source. The abstraction
  (`hms.tenant-archive.kek-source`) is in place so swapping the
  source is a one-bean change.
