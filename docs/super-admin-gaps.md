# Super-Admin Role: Capabilities, Gaps & MVP Roadmap

> Audit date: 2026-05-02 · Baseline: `main` @ 006384fc · Branches:
> `feature/super-admin-gaps` (MVP-1 + MVP-2 — shipped),
> `feature/super-admin-gaps-mvp3-integration-health` (MVP-3 — IN PROGRESS).

## Executive Summary

The super admin is the highest-privilege role in HMS — operating across organizations and hospitals to manage tenants, security policy, feature flags, user governance, and platform health. The current implementation has a **strong backend surface** (8 dedicated `SuperAdmin*` controllers, multi-tenant scoping via `Organization → Hospital`, feature-flag overrides per tenant, security-policy baselines, credential lifecycle, platform registry). The frontend exposes super-admin functions via ~10 separate top-level routes but lacks a **unified super-admin landing page** and several SaaS-grade capabilities (tenant lifecycle, support impersonation, partner-connector status, subscription/quotas).

This document captures all identified gaps and prioritises them by leverage. **MVP-1 (Control Tower) and MVP-2 (Tenant Lifecycle) are scoped to land on this feature branch.** The remaining MVPs are tracked here as the forward queue.

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

## MVP List (Priority Order)

1. **Super-Admin Control Tower** — landing page · *in scope for this branch*
2. **Tenant Lifecycle** — suspend / archive / restore / purge · *in scope for this branch*
3. Partner-Connector / Integration Health Console
4. Support Impersonation with Audit
5. Subscription / Plan / Quotas
6. Emergency Global Controls
7. Cross-Tenant Audit Search UI
8. Data-Residency / Region Tagging

---

## MVP 1: Super-Admin Control Tower (IN SCOPE)

**Goal:** Give a logged-in super admin a single landing page that surfaces every cross-tenant capability they govern.

**Scope:**

- New Angular route `/super-admin` (SUPER_ADMIN only), distinct from the shared `/admin` route.
- Tile / card layout aggregating:
  - Organization count, hospital count, total active users (from `/super-admin/summary`).
  - Cross-tenant alerts: pending security-policy approvals, expired-credential count, MFA non-enrolment rate (from existing endpoints).
  - Feature-flag overrides at-a-glance (count + recent changes from `FeatureFlagOverrideRepository`).
  - Partner-connector health stub (placeholder until MVP 3 lands).
  - Quick links to: organizations, users, roles, feature-flags, analytics, audit-logs, platform registry.
- Move the existing `/admin` view to remain hospital-admin-focused; super admin lands on `/super-admin` instead.
- Side-nav: add a "Super Admin" section visible only when `RoleContextService.isSuperAdmin()`.

**Out of scope:** New backend endpoints. This is pure UI consolidation over existing APIs.

**Priority:** P1 (highest-leverage, lowest cost).

**Complexity:** Low–Medium (Angular only).

**Dependencies:**

- `/super-admin/summary`, `/super-admin/analytics` (already exist).
- `/super-admin/security/policies/approvals/pending` (already exists).
- `/super-admin/credentials/health` (already exists).
- `RoleContextService.isSuperAdmin`.

**Effort:** ~3–5 story points.

**User Stories:**

- *As a super admin*, when I log in, I land on a control-tower page that shows the platform's state at a glance, so I never have to hunt across ten routes.
- *As a super admin*, I see how many security policies are pending my approval, with a one-click jump to the queue.
- *As a super admin*, I see how many users have stale credentials or no MFA, and can drill into the credential-lifecycle tool.

**Acceptance Criteria:**

- Logging in as a `ROLE_SUPER_ADMIN` user redirects to `/super-admin`, not `/admin`.
- The page renders within 1s using existing summary endpoints (no N+1).
- Each tile is keyboard-navigable and has loading/empty/error states.
- Hospital-admin (no SUPER_ADMIN) navigating to `/super-admin` is denied by `RoleGuard` and routed to `/error/403`.
- Unit tests cover: tile rendering with mocked data, role-guard denial, error-state fallback.

**Developer Tasks:**

1. Create `hospital-portal/src/app/super-admin/super-admin.component.{ts,html,scss}`.
2. Add route in `app.routes.ts` with `RoleGuard` (`ROLE_SUPER_ADMIN`).
3. Update post-login redirect in `LoginRedirectGuard` / shell to send super admins to `/super-admin`.
4. Add side-nav entry behind `isSuperAdmin()` signal.
5. Reuse `dashboard.service.ts` / `organization.service.ts` / `platform.service.ts`; add narrow facade if needed.
6. Add Jest specs for component + role guard.

---

## MVP 2: Tenant Lifecycle (IN SCOPE)

**Goal:** Allow a super admin to suspend, archive, restore, and purge an Organization (tenant) and its hospitals, with full audit and a safe two-step confirmation.

**Scope — Backend:**

- Extend `Organization` entity with:
  - `lifecycleState` enum: `ACTIVE`, `SUSPENDED`, `ARCHIVED`, `PENDING_PURGE`, `PURGED`.
  - `suspendedAt`, `suspendedBy`, `suspensionReason`.
  - `archivedAt`, `archivedBy`.
  - `purgeScheduledFor`, `purgedAt`.
- Liquibase migration **V77** (additive only — non-destructive): new columns + index on `lifecycle_state`.
- New endpoints on `SuperAdminOrganizationController`:
  - `POST /super-admin/organizations/{id}/suspend` — sets state, blocks login org-wide.
  - `POST /super-admin/organizations/{id}/restore` — `SUSPENDED` or `ARCHIVED` → `ACTIVE`.
  - `POST /super-admin/organizations/{id}/archive` — soft delete; data retained, hidden from default queries.
  - `POST /super-admin/organizations/{id}/schedule-purge` — sets `purgeScheduledFor` (default 30d).
  - `POST /super-admin/organizations/{id}/cancel-purge`.
  - `GET /super-admin/organizations/{id}/lifecycle` — full audit timeline.
- Login flow: `JwtAuthenticationFilter` rejects tokens whose `permittedOrganizationIds` includes a `SUSPENDED`/`ARCHIVED` org (when not super admin).
- Tenant-scoped queries (`TenantScopeSpecification`) must hide `ARCHIVED`/`PURGED` orgs by default; super admin gets an explicit `includeArchived=true` flag.
- All lifecycle transitions emit `AuditEventLog` entries.
- A scheduled job (`@Scheduled`) executes `PURGED` action when `purgeScheduledFor` is reached — emits export to S3 / object store first, then hard-deletes per-org rows.

**Scope — Frontend:**

- On `/organizations/:id` detail view, add a "Lifecycle" panel showing state + history.
- Action buttons gated to SUPER_ADMIN: Suspend, Restore, Archive, Schedule Purge, Cancel Purge.
- Suspend / Archive / Purge each require: typed-confirmation (org name) + reason text + MFA step-up.
- Surface state in the org list with a colour chip.

**Out of scope (this MVP):** GDPR data-export packaging format, encrypted purge archive — flagged for a follow-up MVP.

**Priority:** P1 (gating real multi-tenant onboarding).

**Complexity:** Medium (schema + endpoints + UI + login-flow change + scheduled job).

**Dependencies:**

- `Organization` entity (existing).
- `JwtAuthenticationFilter` (existing).
- `AuditEventLogService` (existing).
- New: a `TenantLifecycleService`.

**Effort:** ~8 story points.

**User Stories:**

- *As a super admin*, I can suspend an organization (e.g. for non-payment) so all of its users are blocked from logging in until restored.
- *As a super admin*, I can archive an organization that's been off-boarded, hiding it from default views without deleting the data.
- *As a super admin*, I can schedule a purge with a 30-day grace window, which can be cancelled before execution.
- *As a hospital staff member of a suspended org*, I am told plainly that my organization is suspended and given a contact link.

**Acceptance Criteria:**

- Suspend → login attempts return HTTP 423 (`LOCKED`) with a clear message; super admin can still log in.
- Restore from `SUSPENDED` or `ARCHIVED` returns the org to `ACTIVE`; login works again on next attempt.
- Archive removes the org from all default cross-tenant dashboards.
- Schedule-purge requires typed confirmation + MFA; cancel-purge is single-click.
- Purge job runs at the scheduled time, writes an export (placeholder bucket OK for MVP), then deletes org-scoped rows; emits a `TENANT_PURGED` audit event.
- Every state transition is in `AuditEventLog` with actor, timestamp, reason.
- V77 migration is additive only — rollback plan documented.
- Unit + integration tests: state machine transitions, login block on suspend, scheduled-purge dry run.

**Developer Tasks:**

1. Liquibase V77 migration (additive columns).
2. `Organization` entity update + JPA mapping.
3. `TenantLifecycleService` with state machine.
4. `SuperAdminOrganizationController` endpoint additions.
5. `JwtAuthenticationFilter` block on suspended/archived org.
6. Default `TenantScopeSpecification` filter to hide archived.
7. Scheduled `TenantPurgeJob` (off by default in dev profile).
8. Frontend: lifecycle panel, action modals (typed-confirm + MFA), state chip on list view.
9. Tests at every layer (Spring + Jest).
10. Update `docs/super-admin-gaps.md` with closure notes when shipped.

---

## MVP 3: Partner-Connector / Integration Health Console (IN SCOPE — `feature/super-admin-gaps-mvp3-integration-health`)

**Goal:** Give a super admin a single read-only console answering *which
integrations are wired up for which tenants, are they healthy, when did
they last succeed, and what's failing*.

**Scope — Backend:**

- New entity `IntegrationHealthSnapshot` in `clinical` schema (V78,
  additive only) keyed on `(integration_id, organization_id NULL)` with
  `last_status` enum (`HEALTHY` / `DEGRADED` / `FAILING` / `NO_HISTORY`),
  `last_success_at`, `last_failure_at`, `last_error_message`,
  `success_count_24h`, `failure_count_24h`, `counts_window_started_at`.
- `IntegrationHealthRecorder` (`@Component`) with `recordSuccess` /
  `recordFailure` upsert helpers. Runs in its own `REQUIRES_NEW`
  transaction and swallows exceptions so a recorder failure never
  unrolls the caller's primary unit of work. Status derivation:
  `FAILING` when most recent call failed or failures ≥ 50 % of the
  window; `DEGRADED` when both successes and failures present with
  failures < 50 %; `HEALTHY` when last call succeeded and no failures
  in the window.
- `EligibilityServiceImpl` wired to call the recorder after every
  provider invocation with `integration_id = "eligibility"` and the
  caller's organisation id (derived from `Hospital.getOrganization`).
  `EligibilityStatus.ERROR` and `UNKNOWN` map to `recordFailure`,
  everything else to `recordSuccess`.
- `SuperAdminIntegrationHealthService` aggregates the live inventory
  (every `PlatformIntegrationAdapter` bean + a synthetic `eligibility`
  row when at least one `EligibilityProvider` bean is registered) with
  the persisted snapshots. Worst-case status is rolled up across orgs
  per integration.
- New controller `SuperAdminIntegrationHealthController` exposes:
  - `GET /super-admin/integrations` — full inventory grid.
  - `GET /super-admin/integrations/{integrationId}` — per-integration
    drill-down with all org snapshot rows. Throws `ResourceNotFoundException`
    (HTTP 404) for unknown ids.
- Both endpoints gated to `ROLE_SUPER_ADMIN`.
- i18n string `integration.health.notfound` added to EN / FR / default
  bundles.

**Scope — Frontend:**

- New route `/super-admin/integrations` (SUPER_ADMIN only).
- `IntegrationHealthComponent` renders four status chips
  (HEALTHY / DEGRADED / FAILING / NO_HISTORY) and an integration list
  where each row expands to a per-org table showing last success,
  last failure (with error message tooltip), and 24 h counts. Loading,
  error, and empty states are explicit.
- `IntegrationHealthService` calls the two REST endpoints.
- `super-admin` Control Tower gets a new "Integration health" quick-link
  card; sidebar gets an "Integration Health" entry behind
  `RoleContextService.isSuperAdmin()`.
- EN / FR / ES i18n bundles extended with the
  `INTEGRATION_HEALTH.*` namespace.

**Out of scope (this MVP):**

- "Test connection" / "Re-sync now" actions — deferred to MVP-3b once
  adapter call paths exist.
- Per-integration time-series history endpoint — `EligibilityCheck`
  already records every call, but Billing / EHR / Inventory adapters
  have no call sites yet, so a generic event log waits until MVP-3b.
- Actual NHIS / NHIA / CNAMGS / mutuelle connectors (separate
  partner-API track).

**Priority:** P2 (after MVP-1 + MVP-2 shipped on `006384fc`).

**Complexity:** Low–Medium (additive backend + new console UI; no
existing call paths refactored, only EligibilityServiceImpl wired).

**Effort:** ~5 story points.

**Acceptance Criteria — met:**

- V78 ships additively; no destructive changes; rollback note included.
- `EligibilityServiceImpl` records success / failure on every
  `submit()` call (verified by unit tests on the success and error
  paths).
- `IntegrationHealthRecorder` upserts the snapshot, rolls the 24 h
  window, and swallows DB failures (5 unit tests cover create / update
  / window-rollover / DEGRADED-after-failure / DB-down swallowed).
- `SuperAdminIntegrationHealthServiceImpl` lists every adapter even
  when no snapshot exists, rolls FAILING / DEGRADED / HEALTHY worst-case
  per integration, and throws 404 for unknown ids (3 service tests).
- Controller passes through to service and propagates 404 (3 controller
  tests).
- Frontend renders the inventory, errors gracefully on API failure, and
  toggles per-integration drill-down (4 Karma specs).
- EN / FR / ES i18n strings present for all new keys.
- `npm run lint` and `./gradlew :hospital-core:compileJava
  :hospital-core:compileTestJava` clean.

**Developer Tasks — done:**

1. Liquibase V78 migration (additive).
2. `IntegrationHealthStatus` enum + `IntegrationHealthSnapshot` entity.
3. `IntegrationHealthSnapshotRepository`.
4. `IntegrationHealthRecorder` (REQUIRES_NEW, swallowing).
5. `IntegrationHealthSnapshotMapper` (`toDto`).
6. `SuperAdminIntegrationHealthService` interface +
   `SuperAdminIntegrationHealthServiceImpl`.
7. `SuperAdminIntegrationHealthController` (2 endpoints, ROLE_SUPER_ADMIN).
8. Wire `EligibilityServiceImpl` to call the recorder after each
   provider invocation.
9. JUnit + MockMvc tests at every layer (recorder, service, controller).
10. Update existing `EligibilityServiceImplTest` for the new
    constructor arg and verify the recorder is called on success
    and failure.
11. Frontend `integration-health` component triplet + service + model,
    plus Karma spec.
12. Add quick-link card on `/super-admin` + sidebar entry behind
    `isSuperAdmin()`.
13. EN / FR / ES i18n strings.
14. Update this doc.

---

## MVPs 4–8: Forward Queue (not in scope this branch)

| # | MVP | Trigger |
| --- | --- | --- |
| 4 | Support Impersonation with Audit | When first paying tenant onboards and support load is real. |
| 5 | Subscription / Plan / Quotas | First commercial customer; before that, premature. |
| 6 | Emergency Global Controls | Once tenant count > 5 — incident response surface area. |
| 7 | Cross-Tenant Audit Search UI | Compliance audit before SOC2 / equivalent. |
| 8 | Data-Residency / Region Tagging on Organization | Schema decision — tackle before multi-region deployment, even if UI is later. |

## Risks & Open Questions

- **Suspend semantics for super admin:** when an org is suspended, super admins (cross-tenant) must remain able to log in *and* see the org. JWT filter must distinguish.
- **Purge irreversibility:** the 30-day grace + scheduled-job pattern is the safety net. Confirm with stakeholders that 30d is the right default.
- **Hospital-level lifecycle:** this MVP lifts state to `Organization`. Decide if `Hospital` also needs an independent lifecycle (likely yes — defer to a follow-up so this MVP stays scoped).
- **Side-nav restructuring:** introducing `/super-admin` may surface UX questions about whether to retire some of the scattered top-level routes (e.g. fold `/feature-flags` and `/analytics` under `/super-admin/*`). Hold this decision pending MVP 1 review.

## References

- `hospital-core/src/main/java/com/example/hms/config/SecurityConstants.java` — role constants, JWT claims.
- `hospital-core/src/main/java/com/example/hms/controller/SuperAdmin*.java` — 8 controllers.
- `hospital-portal/src/app/app.routes.ts` — route + role guard map.
- `hospital-portal/src/app/core/role-context.service.ts` — `isSuperAdmin` signal.
- `docs/gap.md` — Lab Director gap analysis (prior precedent for this document's structure).
