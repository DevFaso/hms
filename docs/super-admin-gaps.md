# Super-Admin Role: Capabilities, Gaps & MVP Roadmap

> Audit date: 2026-05-02 · Baseline: `main` @ 006384fc · Branches:
> `feature/super-admin-gaps` (MVP-1 + MVP-2 — shipped),
> `feature/super-admin-gaps-mvp3-integration-health` (MVP-3 — open PR),
> `feature/super-admin-gaps-mvp4-support-impersonation` (MVP-4 — IN PROGRESS).

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

## MVP 4: Support Impersonation with Audit (IN SCOPE — `feature/super-admin-gaps-mvp4-support-impersonation`)

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

---

## MVPs 5–8: Forward Queue (not in scope this branch)

| # | MVP | Trigger |
| --- | --- | --- |
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
