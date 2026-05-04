# Super-Admin Role: Capabilities, Gaps & MVP Roadmap

> Audit date: 2026-05-02 (last updated 2026-05-03) · Baseline: `main` @
> `b9f3fe0b` (**MVPs 1–9 + sub-MVPs 4b/5b/6b/7b/8b/9b + MVP-c batch
> backend all in production**; MVP-c frontend surfaces — integration
> probe/resync/history, hospital lifecycle, region-policy editor,
> cross-source audit aggregation — and two backend gaps — MVP-8c
> aggregation projection, MVP-9c routing resolver — deferred to a
> follow-up branch) · `uat` @ `ec2d4a5e` · `develop` @ `273244a3`.
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
> integration_health_event — all additive. **NOT shipped (deferred
> to a follow-up branch):** MVP-3b frontend (Test/Re-sync row
> buttons + 24h history sparkline drawer), hospital-lifecycle
> frontend (detail-page Lifecycle panel + state chip on hospital
> list), MVP-9c policy-editor frontend
> (`/super-admin/data-residency/policy`), MVP-8c cross-source
> aggregation backend (`AggregatedAuditEvent` projection +
> `/audit-search/aggregated` heap-merge endpoint), MVP-9c
> `RegionRoutingResolver` + `TenantProvisioningClient` provisioning
> hook).

## Executive Summary

The super admin is the highest-privilege role in HMS — operating across organizations and hospitals to manage tenants, security policy, feature flags, user governance, and platform health. The current implementation has a **strong backend surface** (8 dedicated `SuperAdmin*` controllers, multi-tenant scoping via `Organization → Hospital`, feature-flag overrides per tenant, security-policy baselines, credential lifecycle, platform registry).

This document originally captured 9 numbered gaps (MVP-1 through
MVP-9) plus a sub-MVP series (MVP-4b through MVP-9b) plus an MVP-c
roll-up batch. **All headline MVPs and sub-MVPs shipped to `main`;
the MVP-c batch shipped backend-only with frontend gaps for four
surfaces.** This doc has been pruned to the live work — completed
sections are removed, see git history for the historical record.
Current gaps and the planned MVP-c2 follow-up branch are below.

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

- **Hospital lifecycle UI:** backend shipped in MVP-c (V84 migration
  + service + JWT login-block + 6 endpoints + MFA step-up) but the
  Angular surface — hospital-detail page with Lifecycle panel, action
  buttons, state chip on the list — is unwritten. An operator can
  suspend a hospital today only via direct REST. Tracked as MVP-c2
  item #2.
- **Region-driven enforcement scope creep:** MVP-9c shipped retention
  + export-format overrides as real code, but the `target_deployment_url`
  column on `region_policy` is stored and never read. Provisioning is
  still single-deployment. Adding the `RegionRoutingResolver` +
  `TenantProvisioningClient` hook is small (config-driven; physical
  multi-region deployment stays an ops task), tracked as MVP-c2
  item #5. Pull when the first GDPR-tagged tenant requesting
  region-specific provisioning lands.

## References

- `hospital-core/src/main/java/com/example/hms/config/SecurityConstants.java` — role constants, JWT claims.
- `hospital-core/src/main/java/com/example/hms/controller/SuperAdmin*.java` — 8 controllers.
- `hospital-portal/src/app/app.routes.ts` — route + role guard map.
- `hospital-portal/src/app/core/role-context.service.ts` — `isSuperAdmin` signal.
- `docs/gap.md` — Lab Director gap analysis (prior precedent for this document's structure).

---

## MVP-c batch (SHIPPED to main `b9f3fe0b` — backend only; frontend deferred)

Per the user's "all in one branch" directive on 2026-05-03, the seven
remaining deferred items rolled up into a single feature branch off
`develop` `c4c9feb9` and shipped on 2026-05-03 via direct merge —
develop `273244a3` → uat `ec2d4a5e` → main `b9f3fe0b`, 10 commits.
The branch closed under a "ship the backend, defer the UI" cut: every
item has a working backend + schema + tests on `main`, but four of
the seven new surfaces have **no frontend** yet, and two backend
pieces from the original scope did not land. The follow-up branch
should bundle those five gaps so the MVP-c surfaces become
operator-reachable.

### Closure status — what shipped vs. what didn't

**Migration-number correction.** Earlier drafts of this doc cited
V84 for the integration health-event log and V88 for the region
policy. The actual on-disk state on `main` is V84
hospital_lifecycle, V85 subscription_plan_feature_keys_jsonb, V86
region_policy, V87 audit_saved_search, V88 integration_health_event.
The closure rows below cite the correct numbers.

Only items with at least one ❌ side are listed; rows for fully-done
MVP-c items (MVP-2c packager, MVP-5c nginx 301s, MVP-6c jsonb +
plan-tier audit) are removed.

| Item | Backend on main | Frontend on main |
| --- | --- | --- |
| MVP-3b probe / resync / per-integration time-series | ✅ `POST /super-admin/integrations/{id}/probe` + `/resync` + `GET /history`; `IntegrationConnectivityProbe` + `Resyncable` SPI + `Probe` record; `CnamgsConnector`, `MutuelleConnector`, `NhiaConnector`, `NhisConnector` stubs via `StubPartnerConnector`; `IntegrationHealthEvent` (V88) records every recorder call for the time-series | ❌ **Missing.** `IntegrationHealthService` exposes only `getInventory()` + `getIntegration()`. The Integration Health Console renders the same MVP-3 surface — chips + list + per-org expand — with no Test/Re-sync action buttons and no history sparkline drawer. |
| MVP-8c saved-search server-side persistence + sharing | ✅ V87 `audit_saved_search` table, `AuditSavedSearch` entity, repository, `SuperAdminAuditSavedSearchController` (REST CRUD), `AuditSavedSearchServiceImpl`. | ❌ Frontend still on the localStorage `AuditSavedSearchService` from MVP-8b — no migration shim, no `?include=shared` toggle, no shared-by attribution. |
| MVP-8c cross-source audit aggregation | ✅ Shipped on `feature/super-admin-mvp-c2-backend`: `AuditSource` enum + `AggregatedAuditEventDTO` + `AggregatedAuditPageDTO`; `SuperAdminAuditAggregationServiceImpl` unions `audit_event_logs` / `frontend_audit_events` / `permission_matrix_audit_events` with timestamp-DESC merge + per-source row cap (5 000); `GET /super-admin/audit-search/aggregated` with optional `sources` array + `fromDate` / `toDate` query params, gated `ROLE_SUPER_ADMIN`. | ❌ Frontend tab/drawer surfacing the merged feed in the audit-search page — pending. |
| MVP-9c per-region retention + export-format policy | ✅ V86 `region_policy` table, `RegionPolicy` entity, `RegionPolicyServiceImpl`, `SuperAdminRegionPolicyController` (`GET /` + `GET /{region}` + `PUT /{region}`). `TenantPurgeExecutor.resolveRetentionDays(region)` and `TenantExportPackager.resolveDefaultExportFormat(region)` consult the policy with global-retention fallback when the per-region override is null. | ❌ **Missing.** `/super-admin/data-residency/policy` view (per-region table with editable retention / export-format / deployment-URL columns) does not exist. The existing `data-residency` component still ships only the MVP-9 region-tag editor. |
| MVP-9c region-routing scaffold | ✅ Shipped on `feature/super-admin-mvp-c2-backend`: `RegionRoutingResolver` (interface + impl) reads `region_policy.target_deployment_url` via `RegionPolicyService`; `TenantProvisioningClient` interface + `StubTenantProvisioningClient` (`@ConditionalOnMissingBean`) that throws `RemoteProvisioningNotConfiguredException` (HTTP 501); wired into `SuperAdminOrganizationProvisioningServiceImpl.createOrganization` so a region with a configured target URL fails loud until a real client is registered. **Strict-mode default** — silent local fallback was rejected as a data-residency risk for GDPR-tagged regions. | n/a — backend-only (provisioning side-effect) |
| Hospital-level lifecycle state machine | ✅ V84 migration, `HospitalLifecycleState` enum, `Hospital.lifecycle_status` + `suspended_at` + `archived_at` + `purge_scheduled_for`, `HospitalLifecycleServiceImpl`, `SuperAdminHospitalLifecycleController` (`GET /lifecycle`, `POST suspend/restore/archive/schedule-purge/cancel-purge`), `HospitalLifecycleStatusServiceImpl` cache (30s TTL, static final), `JwtAuthenticationFilter` login-block, MFA step-up coverage | ❌ **Missing.** No hospital-detail page exists; `hospitals/` ships only `hospital-list.{ts,html,scss}`. No Lifecycle panel, no action buttons, no state chip on the hospital list. |

### Outstanding follow-up branches

**MVP-c2-backend** (this branch — `feature/super-admin-mvp-c2-backend`,
unmerged): closes the two remaining backend gaps.

+ ✅ **MVP-8c cross-source aggregation backend** —
  `AuditSource` enum, `AggregatedAuditEventDTO`, `AggregatedAuditPageDTO`,
  `SuperAdminAuditAggregationService(Impl)` unioning all three audit
  sources, `GET /super-admin/audit-search/aggregated`. Per-source row
  cap (5 000) prevents runaway queries; `LocalDateTime` bounds are
  converted to UTC `Instant` for the permission-matrix entity which
  uses `Instant`.
+ ✅ **MVP-9c routing scaffold** — `RegionRoutingResolver`,
  `TenantProvisioningClient` interface, `StubTenantProvisioningClient`
  (`@ConditionalOnMissingBean`, HTTP 501 strict mode),
  `RemoteProvisioningNotConfiguredException`, wired into
  `SuperAdminOrganizationProvisioningServiceImpl`.
+ 17 new tests (4 resolver + 2 stub client + 5 aggregation service +
  2 provisioning routing-path); full `:hospital-core:test` green.

**MVP-c2-frontend** (next branch): the four UI surfaces.

1. **MVP-3b frontend** — `IntegrationHealthService.probe(id)` +
   `resync(id)` + `getHistory(id, windowHours)`, two action buttons
   on each integration row, expandable history drawer with a
   24-hour sparkline of the bucketed counts.
2. **Hospital-lifecycle frontend** — new `hospitals/hospital-detail`
   page with a Lifecycle panel mirroring the org-detail pattern
   from MVP-2; add a state chip on `hospital-list.html`. New
   `HospitalLifecycleService` calling the six existing endpoints.
3. **MVP-9c policy-editor frontend** — `/super-admin/data-residency/
   policy` route with a per-region table editing retention /
   export-format / deployment-URL.
4. **MVP-8c aggregation UI** — tab or drawer surfacing the merged
   feed in the existing audit-search page; `localStorage` → REST
   migration shim for the saved-search service.

The historical per-item descriptions below remain valid as the
*intended* scope; treat the closure table above as authoritative for
what is actually on `main` `b9f3fe0b`.

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

### Caveats

- **MVP-3b partner protocols are stubs.** `CnamgsConnector`,
  `MutuelleConnector`, `NhiaConnector`, `NhisConnector` extend
  `StubPartnerConnector` and return synthetic success on probe /
  resync. Real wire protocols drop in via the same SPI once partner
  specs + sandbox credentials land.
- **MVP-2c KEK source** defaults to `noop` in dev profiles.
  Production must export `HMS_TENANT_ARCHIVE_KEK` (base64) until a
  real KMS integration replaces the env-source. The abstraction
  (`hms.tenant-archive.kek-source`) is in place so swapping the
  source is a one-bean change.
