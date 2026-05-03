# Super-Admin Role: Capabilities, Gaps & MVP Roadmap

> Audit date: 2026-05-02 (last updated 2026-05-03) · Baseline: `main` @
> `6530997f` (MVPs 1–8 all in production) · `uat` @ `188fd9e4` ·
> `develop` @ `9c06ef22`. Branches:
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
> coverage 0.3% → 94.3%, and 16 SonarCloud code-smell findings).

## Executive Summary

The super admin is the highest-privilege role in HMS — operating across organizations and hospitals to manage tenants, security policy, feature flags, user governance, and platform health. The current implementation has a **strong backend surface** (8 dedicated `SuperAdmin*` controllers, multi-tenant scoping via `Organization → Hospital`, feature-flag overrides per tenant, security-policy baselines, credential lifecycle, platform registry).

This document captures all identified gaps and prioritises them by leverage. **MVPs 1–9 are all in production on `main`.** MVP-1 (Control Tower) + MVP-2 (Tenant Lifecycle) shipped on `006384fc` (2026-05-02). MVP-3 (Integration Health Console) + MVP-4 (Support Impersonation with Audit) + MVP-5 (Super-Admin Surface Consolidation) shipped on `34cd0c56` (2026-05-03 batch1). MVP-6 (Subscription / Plan / Quotas) + MVP-7 (Emergency Global Controls) + MVP-8 (Cross-Tenant Audit Search UI) shipped on `6530997f` (2026-05-03 batch2). **MVP-9 (Data-Residency / Region Tagging) shipped on the same day as a follow-up branch** alongside two pre-existing UX defects from the batch2 release: the Subscriptions "New plan" button was inert (form-card visibility was gated on populated form fields, hidden right after the reset) and the EN-only i18n bundles meant the Subscriptions / Emergency / Audit-Search pages rendered raw `KEY.NAME` strings under FR/ES locales.

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
9. **Data-Residency / Region Tagging** · *shipped on `feature/super-admin-gaps-doc-refresh` — V82 migration + region picker + audit*

---

## MVP 1: Super-Admin Control Tower (SHIPPED to main `006384fc` via promote chain — historical)

> Retrospective note: this MVP introduced `/super-admin` as the
> intended single landing page, but did **not** retire the existing
> super-admin views on `/dashboard` and `/admin`. Both remain
> reachable, creating the duplication that MVP-5 now closes out.
> The original "Side-nav restructuring" risk-and-open-question
> below pre-empted exactly this — it has been folded into MVP-5.

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

## MVP 2: Tenant Lifecycle (SHIPPED to main `006384fc` via promote chain — historical)

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

## MVP 4: Support Impersonation with Audit (MERGED into develop `bbf09844` via PR #224)

**Goal:** Let a super admin act as another (non-super-admin) user for
support purposes, with every action under that session traceable back to
the real human.

**Scope — Backend:**

- V79 migration (additive): `support.audit_event_logs` gains
  `impersonator_user_id UUID` + `impersonator_username VARCHAR(255)`,
  partial index on `(impersonator_user_id, event_timestamp DESC) WHERE
  impersonator_user_id IS NOT NULL` for forensic queries.
- New JWT claims `CLAIM_IMPERSONATOR_USER_ID` + `CLAIM_IMPERSONATOR_USERNAME`
  in `SecurityConstants`. JWT subject + roles claim represent the
  *target* user so all downstream RBAC and `TenantScopeSpecification`
  behave as if the target had logged in.
- `JwtTokenProvider.generateImpersonationAccessToken(target,
  impersonatorUserId, impersonatorUsername, ttlMillis)` mints a
  short-lived (default 30 min, configurable via
  `hms.support-impersonation.ttl-ms`) token. **No refresh token is
  issued** — when the TTL expires the super admin must call `start`
  again. This caps the blast radius of a leaked impersonation token at
  the chosen TTL.
- `JwtTokenProvider.extractImpersonationContext(token)` reads the two
  claims and wraps them in an `ImpersonationContext`.
- `ImpersonationContext` (record) + `ImpersonationContextHolder`
  (thread-local utility class mirroring `HospitalContextHolder`).
  `JwtAuthenticationFilter` populates the holder from the JWT claims
  and clears it in every `finally` / failure branch alongside
  `HospitalContextHolder.clear()`.
- `AuditEventLogServiceImpl` reads the holder before persisting and
  auto-stamps `impersonator_user_id` / `impersonator_username` on every
  audit row. Boundary events (start/stop) bypass the holder by setting
  the impersonator fields explicitly on the request DTO so the audit
  trail is complete even when the JWT subject is the actor (start) or
  when the holder is cleared mid-call (stop).
- `AuditEventType.IMPERSONATION_STARTED` + `…ENDED` enum values.
- `SupportImpersonationService` interface + impl with three operations:
  - `start(request, mfaToken)` — ROLE_SUPER_ADMIN, validates target
    exists, target is not super admin (anti-collusion), target is not
    self, no nested impersonation, MFA step-up via the existing
    `X-Mfa-Token` plumbing reused from MVP-2. Mints the token, emits
    `IMPERSONATION_STARTED`, returns DTO with `accessToken` +
    `expiresAt` + actor + target info.
  - `stop()` — emits `IMPERSONATION_ENDED`. The JWT subject at this
    call is the target; the impersonator is read off the holder.
  - `getActive()` — reflects the holder state for the frontend.
- `SuperAdminImpersonationController` exposes `POST /super-admin/
  impersonation/{start,stop}` and `GET .../active`. `start` is gated
  to `ROLE_SUPER_ADMIN`; `stop` and `active` to `isAuthenticated()`
  because the bearer is the impersonation token (which carries the
  target's roles, not super admin).
- Cross-tenant audit response DTO + mapper carry the new impersonator
  fields so the existing audit-log UI (and the future MVP-7 cross-tenant
  audit search) can surface them.

**Scope — Frontend:**

- New `ImpersonationService` (`/super-admin/impersonation/{start,stop,active}`)
  with a `signal` mirroring active state. `start()` saves the original
  super-admin token under `sessionStorage['auth_token_pre_impersonation']`
  before swapping in the impersonation token; `stop()` restores it.
  `forceStop()` drops the impersonation token without hitting the
  server (used on 401 / TTL expiry). `refreshActive()` re-hydrates the
  signal on shell mount so a page refresh while impersonating re-paints
  the banner.
- Persistent red banner (`ImpersonationBannerComponent`) at the top of
  every authenticated route showing "You are acting as $target as
  $impersonator" plus an **Exit impersonation** button. Wired into the
  shell so it survives navigation. Banner is a real `<button>` with
  `aria-expanded`/focus handling; SCSS uses CSS-only animation so the
  banner is visible even when JS is mid-render.
- "Impersonate this user" icon button on each row of `/users`, gated on
  super admin + target active + target not super admin + not currently
  impersonating. Click opens an inline modal collecting reason
  (≥ 5 chars, persisted in audit) + MFA code (sent as `X-Mfa-Token`).
  Submit calls `ImpersonationService.start` and on success routes to
  `/dashboard` so the super admin lands on the target's home view.
- EN / FR / ES `IMPERSONATION.*` i18n bundles.

**Out of scope (this MVP):**

- "Impersonate" button on org-detail / user-detail pages — list view is
  the most common entry point and ships first.
- Browser warning / countdown timer when impersonation is about to
  expire — defer to MVP-4b along with auto-stop on 401 from the JWT
  filter rejecting an expired token.
- Cross-tenant audit search UI surfacing impersonator filter — that's
  MVP-7. The DTO + mapper already carry the data so MVP-7 only needs
  the search UI.

**Priority:** P2 (after MVP-3 lands).

**Complexity:** Medium-High (auth-flow change + JWT mint path + new
context holder + UI banner + per-row entry point + boundary audit
discipline).

**Effort:** ~8 story points.

**Acceptance Criteria — met:**

- V79 ships additively with rollback-safe partial index. No destructive
  changes; pre-existing rows stay at NULL.
- Super admin can mint an impersonation token with reason + MFA;
  rejected for self-impersonation, target=super-admin, nested
  impersonation, missing/invalid MFA when actor is enrolled.
- Unenrolled actor in non-strict mode passes through but the bypass is
  audited as `SECURITY_ALERT_TRIGGERED` (matches MVP-2 tenant-lifecycle
  pattern).
- Every action under the impersonation token carries
  `impersonator_user_id` + `impersonator_username` on its audit row
  (auto-stamped from the request-scoped holder).
- Frontend banner appears on every authenticated route during
  impersonation; "Exit impersonation" calls `stop` and routes back to
  `/super-admin`. Page refresh re-paints the banner via
  `refreshActive()` on shell mount.
- All boundary transitions (`start` and `stop`) emit
  `IMPERSONATION_STARTED` / `IMPERSONATION_ENDED` with the impersonator
  set explicitly on the request DTO — independent of the holder.
- 8 backend tests (`SupportImpersonationServiceImpl` + controller +
  audit-log entity), 8 frontend tests (`ImpersonationService` +
  banner). `./gradlew :hospital-core:compileJava
  :hospital-core:compileTestJava` clean; `npm run lint` clean; full
  788-test Karma sweep green.

**Developer Tasks — done:**

1. Liquibase V79 migration (additive).
2. Entity + request DTO + response DTO + mapper updated for
   impersonator fields.
3. Two new `AuditEventType` values.
4. JWT claim constants + `JwtTokenProvider` builder + extractor.
5. `ImpersonationContext` + holder.
6. `JwtAuthenticationFilter` wires the holder into the per-request
   lifecycle and clears it in every cleanup branch.
7. `AuditEventLogServiceImpl` auto-stamps from the holder; explicit
   request fields take precedence so boundary events stay complete.
8. `SupportImpersonationService` + impl + DTOs + controller (3
   endpoints, role-gated).
9. JUnit + MockMvc tests at every layer.
10. Frontend `ImpersonationService`, model, banner component, shell
    integration, user-list "Impersonate" button + inline modal.
11. EN / FR / ES i18n strings.
12. Update this doc.

**Copilot review on PR #224 — six findings, all addressed in fixup
commit `db49e73e`:**

| # | Severity | Finding | Fix |
| --- | --- | --- | --- |
| 1 | High (UX) | After stop, `RoleContextService` and stored profile stay on the impersonated target → `RoleGuard` bounces operator to `/error/403`. | `ImpersonationService.restoreOriginalSession` now restores from a sessionStorage profile snapshot taken at start, falling back to a JWT-claim decode of the restored token. |
| 2 | **Critical (security)** | Remember-me login leaves the original token in `localStorage`; `setToken(impersonationToken, false)` writes to `sessionStorage` but `getToken()` prefers `localStorage` → impersonation token never wins. | `AuthService.setToken` now clears the *opposite* storage on every write. New `isTokenRemembered()` helper. Backend defense in depth: `start()` blacklists the original super-admin JTI immediately so the stale token fails auth even if a client somehow keeps reading it. |
| 3 | High (UX) | Token swap doesn't refresh `RoleContextService` or stored profile → super-admin nav stays visible during impersonation, in-memory state disagrees with active token. | `ImpersonationService.start` decodes the new JWT and re-hydrates `RoleContextService` (roles + permittedHospitalIds + activeHospitalId) + the persisted user profile. Snapshot taken before swap so `stop()` can restore bit-for-bit. |
| 4 | **Critical (security)** | Surviving refresh cookie + global 401 interceptor → impersonation TTL elapses → silent refresh into super-admin token, no `IMPERSONATION_ENDED` audit. Privilege escalation. | New `ImpersonationSessionTracker` (in-memory, lock-free, lazy expiry) records active sessions. `AuthController.refreshToken` returns **403 — Active support-impersonation session** when the cookie's subject has an active session. Frontend `errorInterceptor` checks `ImpersonationService.isActive()` and `forceStops` instead of triggering refresh. |
| 5 | High (UX/security) | `restoreOriginalToken` always passes `remember=true` → exiting impersonation silently promotes a session-only login into `localStorage`. | Original `remember` flag persisted under `auth_remember_pre_impersonation` in sessionStorage; `stop()` restores with the saved flag. |
| 6 | **Critical (security)** | `stop()` only emits the audit + tells frontend to discard the token. The impersonation JWT is **not blacklisted** → a copied token (curl, devtools, browser plugin) keeps authenticating until 30-min natural expiry. | `SupportImpersonationServiceImpl.stop` now blacklists the impersonation JWT's JTI via the existing `TokenBlacklistService`. Blacklist failures are swallowed with a warn so the boundary action still succeeds. |

**SonarCloud:** coverage on `SupportImpersonationServiceImpl` lifted
from 79.3 % to clear the 80 % gate by adding 4 new service tests
(tracker-rejects-existing-session / null-bearer-tolerated /
blacklist-failure-swallowed / stop-without-context-rejects) plus the
new `ImpersonationSessionTrackerTest` class (5 tests).

---

## MVP 3: Partner-Connector / Integration Health Console (MERGED into develop `b280a0bd` via PR #223)

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

## MVP 5: Super-Admin Surface Consolidation (SHIPPED to main `34cd0c56` via PR #225 → promote chain)

**Closure notes (this branch):**

- New `SuperAdminRedirectGuard` (`hospital-portal/src/app/auth/super-admin-redirect.guard.ts`)
  redirects an active `ROLE_SUPER_ADMIN` from `/dashboard` and `/admin` to
  `/super-admin`. Other roles fall through. Wired into both routes in
  `app.routes.ts`; `/admin` keeps `RoleGuard` chained behind it so
  `ROLE_ADMIN` still gates the hospital-admin landing.
- Dashboard cleanup: deleted the `'superadmin'` branch from
  `activeView`, `heroGradientClass`, and `loadDashboardData`; deleted the
  `adminSummary` + `recentAuditEvents` signals, the `adminNavTiles`
  computed, the `SuperAdminSummary` / `RecentAuditEvent` imports, the
  hero stat strip, and the rendered Super-Admin section in
  `dashboard.html`. Removed the `.hero-gradient-superadmin` selector
  from `dashboard.scss`. The `isSuperAdmin` signal stays — it still
  guards the hospital-admin loader (`!this.isSuperAdmin()`) and the
  defensive role-label branch.
- Side-nav: when `roleContext.activeRole === 'ROLE_SUPER_ADMIN'`,
  `shell.ts` `baseNavItems()` skips the **Dashboard** entry (Control
  Tower is the landing page) and the **Administration** entry (super
  admins are redirected there, so a duplicate side-nav slot was just
  noise). `appendSuperAdminEntry` already adds **Super Admin** +
  **Integration Health** for super-admin actives.
- Parity sweep: the deleted dashboard super-admin block rendered a
  stat strip (8 tiles) + nav-tile grid (13 tiles) + Recent Audit
  table. The Control Tower (`super-admin.html`) already renders the
  equivalent stats grid, the Quick Links grid (9 cards including
  Integration Health from MVP-3), and the Recent Audit list — no
  user-visible content was lost.
- Tests: 4 new `SuperAdminRedirectGuard` specs (super-admin redirect /
  fallback / hospital-admin pass-through / multi-role active picker)
  plus 3 new `ShellComponent` specs (super-admin nav drops Dashboard
  and Administration / admin keeps both / doctor keeps Dashboard
  only). Removed the `adminNavTiles returns 13 tiles for super-admin`
  and the `roleLabel resolves DASHBOARD.ROLE.SUPER_ADMIN` cases from
  `dashboard.spec.ts` along with the `'superadmin'` view branch they
  exercised. `npm run lint` clean; `format:check` clean; full Karma
  sweep 801 specs green (up from 796).
- Out of scope (deferred to MVP-5b): folding `/feature-flags`,
  `/analytics`, `/audit-logs`, `/platform` under a `/super-admin/*`
  hierarchy.

**Goal:** Make `/super-admin` the **single** mission-control for super
admins. Today three landing surfaces co-exist and a super admin can
arrive at any of them, each rendering a slightly different overview of
the same data — confusing for operators, embarrassing in demos, and
expensive to maintain in lock-step. MVP-1 introduced `/super-admin`
without retiring the duplicates; this MVP closes that loop.

**The current duplication:**

| Surface | What it renders for super admin | Status |
| --- | --- | --- |
| `/super-admin` ([super-admin.ts](../hospital-portal/src/app/super-admin/super-admin.ts)) | Control Tower: stats grid + platform integrations strip + quick-links grid (org / users / roles / feature-flags / analytics / platform / audit-logs / hospitals / **integration health** from MVP-3). Now the explicit `LoginRedirectGuard` target for super admins. | **Keep — single home.** |
| `/dashboard` super-admin branch ([dashboard.html:207-383](../hospital-portal/src/app/dashboard/dashboard.html#L207-L383), guarded by `activeView() === 'superadmin'`) | Stat strip (patients / active users / active hospitals / active orgs / departments) + clinical alerts + quick actions + recent audit. Reachable from the Dashboard side-nav entry. | **Retire.** |
| `/admin` ([admin/admin.ts](../hospital-portal/src/app/admin/admin.ts), gated to ADMIN + SUPER_ADMIN, calls `/super-admin/summary`) | Hospital-admin-shaped dashboard. Super admin can navigate to it from the side-nav and see overlapping summary tiles. | **Hide from super-admin nav** (keep for ADMIN). |

**Scope — Frontend only (no backend changes):**

- Delete the `'superadmin'` view branch from `dashboard.ts` /
  `dashboard.html` — the file's role-aware switch keeps the
  HOSPITAL_ADMIN, DOCTOR, NURSE, RECEPTIONIST, PHARMACIST, etc.
  branches untouched.
- Make `/dashboard` redirect to `/super-admin` when the active role is
  SUPER_ADMIN (`RoleGuard`-style). This handles bookmarks + the side-nav
  click path.
- Make `/admin` redirect the same way for super admins (hospital-admin
  callers continue to land on `/admin`).
- Update [shell.ts](../hospital-portal/src/app/shell/shell.ts) — when
  `roleContext.activeRole === 'ROLE_SUPER_ADMIN'`, the side-nav drops
  the **Dashboard** + **Admin** entries and keeps only the **Super
  Admin** + **Integration Health** entries (already added by MVP-1 +
  MVP-3) plus the cross-cutting routes super admins genuinely need
  (Organizations, Hospitals, Users, etc.).
- Parity sweep: anything unique to the deleted `dashboard` super-admin
  branch (e.g. **Recent Audit** strip) gets folded into the Control
  Tower if not already there. The MVP-1 spec listed "pending
  security-policy approvals, expired-credential count, MFA non-enrolment
  rate" as Control Tower content; verify those tiles are present and
  add the Recent Audit strip if missing.
- Existing Karma specs covering the dashboard super-admin branch get
  deleted; new specs cover the redirect + the side-nav entries shown /
  hidden by active role.

**Out of scope (deferred to MVP-5b):**

- Folding scattered super-admin-only routes (`/feature-flags`,
  `/analytics`, `/audit-logs`, `/platform`) under a `/super-admin/*`
  hierarchy. Doable but introduces redirect debt and changes URL
  bookmarks across the team — keep out of MVP-5 to ship the
  consolidation cleanly first.
- Hospital-admin dashboard restructuring. ADMIN + HOSPITAL_ADMIN
  landings unchanged.

**Priority:** P3 (cosmetic UX correctness; not blocking customer
adoption but a credibility issue once a prospect starts clicking around
the super-admin surface in a demo).

**Complexity:** Low (Angular only — net deletion + small route
guard + side-nav filter).

**Effort:** ~3–5 story points.

**Acceptance Criteria:**

- A super admin who navigates to `/dashboard` is redirected to
  `/super-admin`. Same for `/admin`.
- Side-nav for `activeRole === 'ROLE_SUPER_ADMIN'` does not include
  `Dashboard` or `Admin` entries. Hospital-admin nav unchanged.
- Control Tower (`/super-admin`) renders all content the deleted
  `dashboard` super-admin branch rendered (parity check covers the
  Recent Audit strip).
- Visual regression sweep on `/dashboard` for the **non-super-admin**
  roles confirms nothing else moved.
- Karma specs deleted: any test covering `activeView() === 'superadmin'`.
  Karma specs added: redirect tests, side-nav role-filter test.
- `npm run lint`, `format:check`, full Karma sweep clean.

**Developer Tasks:**

1. Delete the `'superadmin'` block from `dashboard.html` and the
   matching `if (this.isSuperAdmin())` branches from `dashboard.ts`
   (`activeView`, `setupSuperAdminLoaders`, etc.). Trace via `grep -n
   'isSuperAdmin' hospital-portal/src/app/dashboard/dashboard.ts`.
2. Add a `SuperAdminRedirectGuard` (or extend the existing
   `LoginRedirectGuard` logic) that redirects `/dashboard` and `/admin`
   to `/super-admin` when `activeRole === 'ROLE_SUPER_ADMIN'`.
3. In [shell.ts](../hospital-portal/src/app/shell/shell.ts)
   `baseNavItems()`, exclude the Dashboard and Admin items when
   `activeRole === 'ROLE_SUPER_ADMIN'` (mirrors the existing pattern
   that builds the patient-portal nav).
4. Parity sweep: list every signal / API call backing the deleted
   `dashboard` super-admin branch; confirm Control Tower covers each
   or fold it in.
5. Delete dashboard.spec.ts cases that cover the super-admin branch.
6. Add `dashboard.spec.ts` test: super-admin user navigating to
   `/dashboard` is redirected.
7. Add `shell.spec.ts` test: nav for `ROLE_SUPER_ADMIN` excludes
   Dashboard and Admin.
8. Update [docs/super-admin-gaps.md](../docs/super-admin-gaps.md) with
   closure notes.

---

## MVP 6 + MVP 7 + MVP 8 — bundled trio (SHIPPED to main `6530997f` via PR #228 → promote chain)

Three MVPs delivered as one PR per the user's "all in one" instruction
on 2026-05-03. Order of implementation within the branch: MVP-8 (smallest,
mostly frontend) → MVP-7 (backend + UI, touches the JWT filter) → MVP-6
(largest schema work).

**Promote chain:** develop `9c06ef22` (merge of feature branch) →
uat `188fd9e4` via PR #229 (batch2) → main `6530997f` via PR #230
(batch2). Carries the feature commit + 6 fixup commits:

1. **CI build break** — `audit-search.html` `@for` track expression
   used nullable string fields (TS2531); surfaced `AuditEventLog.id`
   on the response DTO + new `toDtoLite` mapper variant; track is now
   `row.id`. Dropped unused `DatePipe` import from
   `SubscriptionsComponent` (NG8113).
2. **PR #228 Copilot review (10 findings)** — most critical: V80 + V81
   were not referenced from `db/migration/changelog.xml` (Liquibase
   would never apply them in any environment using the changelog),
   and the emergency endpoints advertised `X-Mfa-Token` enforcement
   but the controller never read the header (super admin could
   trigger force-logout / kill-feature / MFA reset / broadcast on a
   plain bearer token). Both fixed; service-layer `verifyMfaStepUp`
   now mirrors the MVP-4 impersonation pattern (enrolled → verify;
   unenrolled non-strict → audit bypass; unenrolled strict → reject).
   Also: `assignPlan` rejects deactivated plans, `billingPeriod` is a
   typed enum on the DTO (Spring 400s on unknown values), `cancel`
   verifies subscription belongs to the org on the URL,
   `SuperAdminAuditSearchServiceImpl` collapses redundant joins / drops
   unconditional `distinct(true)` / uses `toDtoLite` to avoid N+1
   `PatientRepository` lookups, `GlobalSessionRevocationService.refresh`
   resets cache to `EPOCH` on missing singleton row, audit-search
   frontend status pill distinguishes SUCCESS / FAILURE / PENDING /
   unknown.
3. **Test-context fixup** — `JwtAuthenticationFilterTest` +
   `UserRoleHospitalAssignmentControllerTest` +
   `PlatformRegistryControllerTest` were missing `@Mock` /
   `@MockitoBean GlobalSessionRevocationService`, so slice contexts
   could not autowire `JwtAuthenticationFilter` and 19 specs cascaded
   from a single context-load failure.
4. **SonarCloud coverage gate** — coverage on new code was 0.3%; lifted
   to **94.3% lines / 86.8% branches** by adding 5 new test classes
   (+56 specs). Notable trick: invoking `Specification.toPredicate(...)`
   directly with mocked `Root` / `CriteriaBuilder` / `CriteriaQuery`
   to exercise every filter branch in the audit-search lambda
   (otherwise the spec is captured-but-not-invoked and branch
   coverage floors at 5.6%).
5. **SonarCloud code smells (16 findings)** — 6 production: new
   `AuditSearchFilter` parameter object record kills the 12-param +
   11-param + cognitive-complexity-29 findings on
   `SuperAdminAuditSearchService(Impl)`; `FIELD_EVENT_TIMESTAMP` and
   `ERROR_PLAN_NOT_FOUND` constants close 3-duplications findings;
   `JwtAuthenticationFilter.handleValidatedToken` extracted to drop
   cognitive complexity 17 → ≤15. 10 test cleanups: unused import,
   useless `eq(...)` wrappers, `containsEntry` chain, multi-throw
   lambda hoists.

**Final QA gate:** all 13 CI checks green; backend 4783 specs pass;
full Karma sweep 802 specs green; `npm run lint` + `format:check` +
`ng build` clean; SonarCloud quality gate green.

### MVP 8: Cross-Tenant Audit Search UI

- **Backend.** `AuditEventLogRepository` extended with
  `JpaSpecificationExecutor<AuditEventLog>`. New
  `SuperAdminAuditSearchService(Impl)` builds a Specification from the
  optional filter args (actor user id, userName substring, eventType
  list, status, hospital, organization via assignment.hospital
  .organization, **impersonatorUserId** from MVP-4, entityType,
  resourceId, fromDate, toDate). Default sort `eventTimestamp DESC`.
  `SuperAdminAuditSearchController` exposes
  `GET /super-admin/audit-search` gated to `ROLE_SUPER_ADMIN`. Returns
  `AuditSearchPageDTO` (content, pageNumber, pageSize, totalElements,
  totalPages) so the frontend doesn't bind to internal Spring types.
- **Frontend.** New `/super-admin/audit-search` route with filter form
  (user name, impersonator id, entity type, resource id, status, date
  range), paginated results table, and a row-highlighting class for
  impersonated actions. Sidebar entry + Control Tower quick-link card.
  EN i18n added; FR/ES bundles deferred (translate pipe falls back to
  the key, so functional but un-localised — flagged for a follow-up).
- **Out of scope (deferred to MVP-8b).** Cross-source aggregation
  spanning `FrontendAuditEvent` and `PermissionMatrixAuditEvent`; CSV
  export; saved-search persistence.

### MVP 7: Emergency Global Controls

- **Backend.** Liquibase **V80** (additive only) creates singleton
  `security.security_revocations` (id=1, `global_min_token_iat`).
  `SecurityRevocation` entity + repo. `GlobalSessionRevocationService`
  caches the timestamp via `@Scheduled(fixedDelay=30_000)` so multi-
  instance deployments converge within 30 s without Redis pub/sub; a
  hot bump on the same instance is instantaneous via the volatile
  cached field. `JwtAuthenticationFilter` checks
  `iat >= globalMinTokenIat` after the existing blacklist check —
  short-circuits to `respondUnauthorized` on revoked tokens. Defensive:
  the new check returns false (=not revoked) when the cached value is
  EPOCH or the token has no iat claim or extraction throws, so a
  parser hiccup or DB blip never locks every user out.
- New `EmergencyControlService(Impl)` with four ops:
  - `forceLogoutAll(reason)` — calls
    `GlobalSessionRevocationService.revokeAll`, audits as
    `SECURITY_ALERT_TRIGGERED` with prefix `EMERGENCY_FORCE_LOGOUT_ALL`.
  - `killFeature(flagKey, reason)` — calls
    `FeatureFlagService.upsertOverride(flagKey, enabled=false)` and
    audits.
  - `forceMfaReenrol(userIds, reason)` — when `userIds` empty, falls
    back to every user with an enrolment row. Deletes
    `UserMfaEnrollment` rows + `MfaBackupCode` rows for each target,
    forcing re-enrolment on next login.
  - `broadcast(message, severity)` — publishes a STOMP frame to
    `/topic/emergency-broadcast` with `type=EMERGENCY_BROADCAST`,
    `severity`, `issuedBy`, `issuedAt`. Broker failures are swallowed
    with a warn so the audit trail is preserved.
- `SuperAdminEmergencyController` exposes
  `POST /super-admin/emergency/{force-logout-all,kill-feature,force-mfa-reenrol,broadcast}`,
  all `ROLE_SUPER_ADMIN` and validated DTOs (`@NotBlank` reason ≥ 5
  chars).
- **Frontend.** `/super-admin/emergency` console with four panels (one
  per action); each requires reason + MFA code + (for force-logout-all)
  typed-confirmation `FORCE LOGOUT ALL`. `EmergencyControlService`
  posts `X-Mfa-Token` headers reusing the MVP-2 / MVP-4 plumbing.
  Sidebar entry + Control Tower quick-link card.
- **Out of scope (deferred to MVP-7b).** Per-tenant kill-switch (the
  existing `FeatureFlagOverride` is global by design — multi-tenant
  override semantics is a separate design decision). MFA re-enrol
  filtered by hospital. Frontend STOMP subscription that surfaces the
  banner on every authenticated route (the backend already publishes;
  the consumer is small and will land in MVP-7b).

### MVP 6: Subscription / Plan / Quotas

- **Backend.** Liquibase **V81** (additive only) creates
  `platform.subscription_plans` and `platform.organization_subscriptions`.
  Partial unique index `uq_orgsub_active_per_org ON
  organization_subscriptions (organization_id) WHERE status = 'ACTIVE'`
  enforces one active subscription per org while allowing CANCELLED /
  EXPIRED rows to accumulate for billing audits. FK back to
  `hospital.organizations(id)` (Organization lives in the `hospital`
  schema, not `platform`).
- `SubscriptionPlan` + `OrganizationSubscription` entities (extend
  `BaseEntity`) with enum `Status` (ACTIVE / CANCELLED / EXPIRED) and
  `BillingPeriod` (MONTHLY / QUARTERLY / ANNUAL).
  `SubscriptionMapper` follows the project convention (hand-written
  `@Component` mapper, no MapStruct). `SubscriptionService(Impl)`
  exposes plan CRUD + assign/cancel per organization; assignment
  cancels any pre-existing ACTIVE row in the same tx so the partial
  index holds. Throws `ResourceNotFoundException` on missing plan /
  org / subscription.
- `SuperAdminSubscriptionController` exposes 7 endpoints:
  `GET / POST / PUT / DELETE /plans`, `GET / POST /organizations/{id}`,
  `GET /organizations/{id}/active`, `DELETE /organizations/{id}/{subId}`.
  All `ROLE_SUPER_ADMIN`. `featureKeys` is a comma-separated string
  for this MVP — moves to a jsonb column in MVP-6b once enforcement
  against `FeatureFlagOverride` lands.
- **Frontend.** `/super-admin/subscriptions` with plan grid + create/
  edit form + active-only toggle. Sidebar entry + Control Tower
  quick-link card.
- **Out of scope (deferred to MVP-6b).** Plan-tier feature enforcement
  (the JWT filter / FeatureFlagService will consult the active
  subscription's `featureKeys` to gate access). Self-service org-side
  upgrade UI. Proration / mid-period billing changes.

### Trio QA gate

`./gradlew :hospital-core:compileJava :hospital-core:compileTestJava`
clean. Frontend: `npm run lint` clean, `npm run format:check` clean,
full Karma sweep **802 specs green** (super-admin Control Tower spec
updated for the +3 quick-link cards: 9 → 12).

## MVP 9: Data-Residency / Region Tagging (SHIPPED on `feature/super-admin-gaps-doc-refresh`)

**Goal:** Give every organization a first-class data-residency label so the
Control Tower can show which compliance posture applies to each tenant
and so a compliance officer can re-tag a tenant (e.g. an org migrating
from a CNAMGS-managed BF deployment to an EU/GDPR-managed one) with full
audit, without a schema migration each time.

**Closure notes (this branch):**

- Two pre-existing UX defects from the MVP-{6,7,8} batch were fixed
  alongside MVP-9 in the same branch:
  - **Subscriptions "New plan" button was inert.** The form-card was
    gated on `editing() || form().name !== '' || form().tierCode !== ''`,
    all of which are falsy right after `startCreate()` resets the form,
    so the panel never appeared. Fixed with an explicit `formOpen`
    signal flipped on by `startCreate` / `startEdit` and off by
    `cancelEdit`. Also moved the inline error strings
    (`'Name and tier code are required.'`, `'Save failed.'`,
    `'Deactivation failed.'`) to translation keys
    (`SUBSCRIPTIONS.ERROR.{REQUIRED_FIELDS,SAVE_FAILED,DEACTIVATE_FAILED}`)
    so FR/ES users no longer see English error toasts. `npx prettier
    --write` + `npm run lint` clean. New `subscriptions.spec.ts` with 4
    specs locks the regression: form opens after reset / cancel closes
    it / submit emits a translation key not raw English / save-failure
    stores a translation key.
  - **FR/ES bundles missing every super-admin MVP block.** `en.json`
    had `SUBSCRIPTIONS`, `EMERGENCY`, `AUDIT_SEARCH`, `INTEGRATION_HEALTH`,
    `IMPERSONATION`, the new `SUPER_ADMIN.LINK.*` cards, plus the
    `NAV.{AUDIT_SEARCH,EMERGENCY_CONTROLS,SUBSCRIPTIONS}` and
    `COMMON.BACK_TO_CONTROL_TOWER` strings — but `fr.json` and `es.json`
    had only `SUPER_ADMIN`, `INTEGRATION_HEALTH`, and `IMPERSONATION`.
    Result: a FR/ES super admin saw raw `SUBSCRIPTIONS.TITLE` /
    `EMERGENCY.WARNING` / `NAV.AUDIT_SEARCH` everywhere. Added the full
    French and Spanish translations for all three top-level blocks +
    the supplementary keys.
- **Backend.** New enum
  `com.example.hms.enums.OrganizationRegion` covers the West/Central
  Africa focus countries (BF / CI / SN / GA / CM / BJ / TG / ML / NE),
  the umbrella `ML_OAPI` for shared UEMOA / OAPI rows, plus `EU` /
  `US` / `OTHER`. `Organization` gains an `@Enumerated(STRING)`
  `region` column with the same `BF` default the V82 migration
  backfills, so legacy rows are guaranteed non-null after the upgrade.
  V82 (additive only) adds `hospital.organizations.region VARCHAR(32)
  NOT NULL DEFAULT 'BF'` and a single index `idx_organization_region`
  for the Control Tower region filter.
- New `OrganizationRegionService(Impl)` exposes `listAvailableRegions`
  (the catalogue, driven by enum order so a new code is one line of
  Java + i18n), `listOrganizationRegions` (per-org snapshot sorted by
  name for a deterministic UI), `getOrganizationRegion`, and
  `updateOrganizationRegion`. Updates emit a new
  `AuditEventType.ORGANIZATION_REGION_UPDATED` with description
  `Organization region changed from <previous> to <next>: <reason>`
  (or `reaffirmed at <next>` when the value is unchanged so a noop
  click still records the operator's intent). Audit failures are
  swallowed so a region change is never rolled back by an audit-store
  hiccup — same pattern as `OrganizationLifecycleServiceImpl.recordAudit`.
- Four new endpoints on `SuperAdminOrganizationController`, all
  `ROLE_SUPER_ADMIN`:
  - `GET /super-admin/organizations/regions` — region catalogue.
  - `GET /super-admin/organizations/region-snapshot` — per-org rows.
  - `GET /super-admin/organizations/{id}/region` — single org read.
  - `POST /super-admin/organizations/{id}/region` — update with
    `OrganizationRegionUpdateRequestDTO` (`@NotNull region` + optional
    `@Size(max=1000) reason`).
- `SuperAdminCreateOrganizationRequestDTO` gains an optional
  `region` field; provisioning service falls back to `BF` when null,
  matching the V82 default.
- `OrganizationResponseDTO` + `OrganizationMapper` now carry the
  region so the existing org list and the Control Tower hierarchy
  view surface the badge without a second round-trip.
- **Frontend.** New `/super-admin/data-residency` route
  (SUPER_ADMIN-only). `DataResidencyComponent` renders:
  - Distribution chip strip (one chip per active region with a
    count, plus an "All" reset chip) that doubles as a filter; click a
    chip to narrow the table.
  - Per-org table (Organization / Code / Region badge / Retag action).
    The badge uses per-region accent classes (`region-badge--bf`,
    `--ci`, `--sn`, `--ga`, `--cm`, `--eu`, `--us`, `--other`) so the
    grid is scannable at a glance.
  - Inline editor opened by **Retag**: dropdown sourced from the
    backend region catalogue + optional reason textarea. Submit calls
    `POST /region` and patches the row in place on success; on failure
    the form stays open and surfaces the
    `ORG_REGION.ERROR.UPDATE_FAILED` translation key.
- New `DataResidencyService` mirrors the four endpoints. Sidebar gets
  a "Data Residency" entry behind `RoleContextService.isSuperAdmin()`
  using a `public` material icon. Control Tower gets a 13th
  quick-link card pointing at `/super-admin/data-residency`.
- EN / FR / ES bundles all carry the new `ORG_REGION.*` namespace
  (TITLE / SUBTITLE / LOADING / LOAD_ERROR / EMPTY / DISTRIBUTION /
  RETAG / EDIT_TITLE / `FIELD.*` / `COL.*` / ERROR.UPDATE_FAILED +
  the 13 per-code labels under
  `ORG_REGION.CODE.{BF,CI,SN,GA,CM,BJ,TG,ML,NE,ML_OAPI,EU,US,OTHER}`)
  plus the matching `SUPER_ADMIN.LINK.REGIONS_*` and
  `NAV.DATA_RESIDENCY` entries.

**Out of scope (this MVP, deferred to MVP-9b):**

- Per-region routing of new tenants to a region-specific deployment
  (the column already records the jurisdiction; physical multi-region
  deployment is a separate infra item).
- Per-region retention overrides (today every tenant follows the
  global retention policy regardless of region).
- Per-region export-format defaults (e.g. GDPR portability vs. HIPAA).
- Region-aware cross-tenant audit-search filter (the existing UI shows
  all rows; adding a "filter by tenant region" needs a join through
  the assignment chain — small but waiting for the first user request).

**Acceptance Criteria — met:**

- V82 ships additively; existing rows backfill to `BF`; rollback is a
  single `ALTER TABLE … DROP COLUMN` with no FK cleanup.
- Region update is idempotent (no `save()` when value unchanged) but
  still audited so the operator's intent is captured.
- Audit row carries the previous → next transition + the optional
  reason in the description; resourceId / resourceName / userName
  populate from the request-scoped `HospitalContextHolder`.
- 5 service-layer tests + 3 controller IT tests + 8 Karma specs cover
  the catalogue / snapshot ordering / unknown-id 404 / change vs.
  noop save / audit-failure swallow / frontend filter / edit submit /
  failure path / cancel.
- `./gradlew :hospital-core:compileJava :hospital-core:compileTestJava`
  clean. `npm run lint` clean, `npm run format:check` clean,
  `ng build` clean, full Karma sweep **814 specs green** (up from 802;
  +8 data-residency, +4 subscriptions, super-admin Control Tower spec
  bumped 12 → 13 quick-links).

**Developer Tasks — done:**

1. `OrganizationRegion` enum + Liquibase V82 (additive,
   default `'BF'`, indexed) + changelog.xml entry.
2. `Organization.region` column + builder default + import.
3. `OrganizationResponseDTO.region` + `OrganizationMapper`.
4. `SuperAdminCreateOrganizationRequestDTO.region` + provisioning
   service fallback.
5. `AuditEventType.ORGANIZATION_REGION_UPDATED`.
6. `OrganizationRegionService` + impl with audit + idempotent update.
7. `OrganizationRegionUpdateRequestDTO` + `OrganizationRegionResponseDTO`.
8. Four new endpoints on `SuperAdminOrganizationController`.
9. `OrganizationRegionServiceImplTest` (5 specs) + extended
   `SuperAdminOrganizationControllerIT` (3 region specs).
10. Frontend `data-residency` component + `DataResidencyService` +
    `data-residency.model.ts` + spec (8 specs).
11. Side-nav entry, Control Tower quick-link card, route registration.
12. EN / FR / ES `ORG_REGION.*` + `SUPER_ADMIN.LINK.REGIONS_*` +
    `NAV.DATA_RESIDENCY` strings.
13. Subscriptions "New plan" fix + `subscriptions.spec.ts` (4 specs).
14. FR/ES backfill of MVP-{6,7,8} translation blocks +
    `COMMON.BACK_TO_CONTROL_TOWER` + the three new NAV entries.
15. Update this doc.

## Risks & Open Questions

- **Suspend semantics for super admin:** when an org is suspended, super admins (cross-tenant) must remain able to log in *and* see the org. JWT filter must distinguish.
- **Purge irreversibility:** the 30-day grace + scheduled-job pattern is the safety net. Confirm with stakeholders that 30d is the right default.
- **Hospital-level lifecycle:** this MVP lifts state to `Organization`. Decide if `Hospital` also needs an independent lifecycle (likely yes — defer to a follow-up so this MVP stays scoped).
- ~~**Side-nav restructuring:**~~ *superseded by MVP-5 (Super-Admin Surface Consolidation), which retires `/dashboard` super-admin branch + `/admin` reachability for super admin and is the next scoped piece of work. Folding `/feature-flags`, `/analytics`, `/audit-logs`, `/platform` under `/super-admin/*` URLs remains deferred to MVP-5b — see MVP-5 "Out of scope".*

## References

- `hospital-core/src/main/java/com/example/hms/config/SecurityConstants.java` — role constants, JWT claims.
- `hospital-core/src/main/java/com/example/hms/controller/SuperAdmin*.java` — 8 controllers.
- `hospital-portal/src/app/app.routes.ts` — route + role guard map.
- `hospital-portal/src/app/core/role-context.service.ts` — `isSuperAdmin` signal.
- `docs/gap.md` — Lab Director gap analysis (prior precedent for this document's structure).
