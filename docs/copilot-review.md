# 2026-05-02 — Copilot review archive

Four PRs in this batch went through Copilot review. Eight comments total;
all addressed below before merging into `develop`.

---

## PR #213 — `feature/doctor-dashboard-styling`

### 1. `doctor-patient-flow.scss` — `display: none` on the stage label removes it from the accessibility tree

> `.flow-col-label` is set to `display: none` for empty columns. That removes
> the stage label from the accessibility tree, leaving only the icon/count
> (and `title`, which isn't reliably exposed on touch devices). Prefer
> keeping an accessible name via `aria-label` / `aria-labelledby` or a
> visually-hidden label (and consider marking the icon as `aria-hidden`).

**Fix:** swapped `display: none` for the standard visually-hidden / sr-only
CSS pattern (`position: absolute; width: 1px; clip: rect(0,0,0,0); ...`)
inside `.flow-col-empty .flow-col-label`. The label text is still in the
DOM and reachable by screen readers; it just doesn't take visual space.
Added `aria-hidden="true"` on the decorative material icon and the count
chip, and an explicit `[attr.aria-label]="col.label + ' (' + count + ')'"`
on `.flow-col-header` so the column has a single, complete accessible name
regardless of empty/non-empty state.

### 2. `dashboard.scss` — `opacity: 0.55` on `.stat-card-zero` drops text contrast below WCAG

> `opacity: 0.55` on `.stat-card-zero` will also dim all child text/icons
> and can drop text contrast below WCAG thresholds (especially with
> `$text-muted`). Consider reducing emphasis without lowering overall
> opacity (e.g., adjust background/border colors, or apply opacity only
> to non-text decorative elements).

**Fix:** removed the card-wide `opacity: 0.55`. Recede now uses
non-text-impacting changes: lighter background (`#f8fafc`), lighter
border (`#e2e8f0` / `#cbd5e1` left accent), no shadow. Opacity is
applied only to `.stat-icon-wrap` (decorative), keeping `.stat-value`
and `.stat-label` text fully opaque so contrast against the new
background still clears WCAG AA. Hover restores shadow + border + icon
opacity for inspection.

---

## PR #216 — `feature/role-dashboards-polish`

### 1. `doctor-patient-flow.html` — accessibility (same root cause as PR #213)

The PR #216 review re-flagged the same `display: none` a11y concern from
PR #213. Resolved by the same fix described above; PR #216 inherits it
because role-dashboards-polish is branched from doctor-dashboard-styling.

### 2. `dashboard.html` — `heroGradientClass()` is computed but never bound

> `heroGradientClass()` is computed in the component and matching
> `.hero-gradient-*` classes exist in `dashboard.scss`, but the
> `<header>` doesn't apply that class, so role-specific gradient styling
> will never take effect. Consider binding the computed class onto the
> header (while keeping `hero-header` and `hero-compact`).

**Fix:** added `[ngClass]="heroGradientClass()"` to the
`<header class="hero-header">` element so each role gets its own
background gradient (`.hero-gradient-doctor`, `.hero-gradient-nurse`,
`.hero-gradient-superadmin`, etc.). Latent dead code is now wired up.

### 3. `dashboard.scss` — comment claims "doctor-only" but the condition is broader

> The comment block says "Doctor view: compact hero… Other roles render
> the default hero unchanged," but `hero-compact` is also applied for
> receptionist and pharmacist in the template. Please update the comment
> to match actual behavior (or narrow the condition if doctor-only is
> intended) to avoid future confusion.

**Fix:** rewrote the comment to enumerate the three roles that get
`.hero-compact` and to explain why (workflow-heavy roles that benefit
from vertical-space conservation), so the comment matches the actual
gating condition.

---

## PR #214 — `feature/backend-dx-startup`

### 1. `StartupSubsystemLogger.java` — MAIL detection too narrow

> MAIL detection is based only on `spring.mail.username` being blank.
> This will miss common misconfigs (username set but password missing)
> and can also false-positive for setups where SMTP auth is disabled.
> Consider checking `spring.mail.host` / `spring.mail.properties.mail.smtp.auth`
> and validating both username + password only when auth is enabled (or
> emit a more accurate message like "mail credentials not configured").

**Fix:** detection rewritten to check `spring.mail.host` first (no host
→ "mail not configured"), then check whether SMTP auth is enabled
(`spring.mail.properties.mail.smtp.auth`, defaulting to true), and only
require username+password if auth is on. Distinct messages for
"no host", "auth required but credentials missing", and "host set with
auth disabled" so the operator can tell at a glance which case applies.

### 2. `StartupSubsystemLogger.java` — Kafka per-flag mismatch goes unannounced

> Kafka "disabled" log only triggers when both `spring.kafka.enabled`
> and `app.kafka.enabled` are false. In this codebase, either flag
> being false effectively disables parts of Kafka (Kafka beans vs.
> app-level publishing), and mismatched values (one true, one false)
> are exactly the cases you likely want to surface.

**Fix:** the two flags are now evaluated and logged independently.
Three explicit outcomes: both off ("Kafka fully disabled"), both on
(no log), or mismatched ("partial Kafka — beans on but app publishing
off" / vice versa). The mismatched cases are logged as `WARN` since
they're almost always misconfiguration, not intent.

---

## PR #215 / #218 — `chore/remove-deprecated-2025`

### 1. `ChatMessageRequestDTO.senderId` removal breaks the Angular chat caller

> Removing `senderId` from `ChatMessageRequestDTO` changes the
> `/chat/send` request contract. The Angular portal still includes
> `senderId` in its send payload (e.g.,
> `hospital-portal/src/app/chat/chat.ts`), which can cause request
> failures if Jackson is configured to fail on unknown properties in
> some environments and also leaves the clients out of sync with the
> OpenAPI schema. Please update the frontend (and any other clients) to
> stop sending `senderId`, or explicitly annotate the request DTO to
> ignore unknown properties for backward compatibility during the
> transition.

**Fix (frontend cleanup, not backward-compat shim):** updated
`chat.ts:221` to drop `senderId` from the `ChatSendRequest` payload —
backend has always derived the sender from `SecurityContext`, and the
field has been documented as ignored since v1.0. Also removed the
field from the `ChatSendRequest` TypeScript type so the compiler
prevents future regressions. Read-side usage at `chat.ts:504`
(`msg.senderId === currentUserId`) is unchanged: that reads from the
*response* DTO (`ChatMessage`), which still carries the server-derived
sender id.

We picked the frontend cleanup over a backward-compat
`@JsonIgnoreProperties(ignoreUnknown = true)` because Jackson in this
project is already lenient by default (no callers were actually 4xx-ing
on extra fields in production) and a permanent shim hides the contract
drift Copilot rightly flagged.

---

## Out-of-scope follow-ups (not addressed in this batch)

- Full Spanish backfill for `PORTAL.{APPOINTMENTS, BILLING, FAMILY,
  SHARING, MEDICATIONS, LAB_RESULTS, VISITS, CARE_TEAM, RECORDS,
  SUMMARIES}` — ngx-translate's English fallback shields the UI today
  but Spanish-speaking patients see English copy across most of the
  patient portal. Tracked separately.
- Backend HTTP-layer test coverage for the seven `SuperAdminDashboardController`
  endpoints — flagged in the audit, deliberately deferred for a focused
  testing PR.
- Auth-flow fix for the MFA chicken-and-egg bug surfaced during this
  session (a doctor with no MFA enrollment cannot enroll because
  `/auth/mfa/enroll` requires full auth that enrollment is supposed
  to grant). Workaround landed on `feature/backend-dx-startup`: MFA
  disabled in the local-h2 dev profile and the NPE replaced with a
  clean 401. The proper fix (accept `mfaToken` for first-time
  enrollment, or issue a scoped enrollment token at login) is deferred.
