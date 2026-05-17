# Copilot review notes (HMS)

Working notes for Copilot review findings across recent PRs. Each item is
summarized and marked fixed after the follow-up changes land. New PRs
append a new H2 section; numbered "Fixed #N" entries are continuous
across PRs so a finding can be referenced unambiguously in a commit
message or PR comment.

---

## Copilot review — PR #329 `feat/v2.0-schema-per-tenant` (2026-05-15)

### Fixed #1 - JDBC connection leak on search_path failure (High)

**File:** `hospital-core/src/main/java/com/example/hms/security/tenant/schema/SchemaTenantConnectionProvider.java`

**Copilot:** `getAnyConnection()` and `getConnection(...)` checked out a JDBC
connection, then called `applySearchPath(...)`. If `SET search_path` failed, the
connection was never closed and could leak from the Hikari pool.

**Resolution:** Fixed. Both acquisition paths now delegate through a single
`connectionWithSearchPath(...)` helper that closes the checked-out connection on
`SQLException` before rethrowing. If `close()` also fails, that exception is
attached as suppressed so the original setup failure remains visible.

**Regression tests:** Added coverage for both default and tenant-specific
connection acquisition failure paths in `SchemaTenantConnectionProviderTest`.

---

### Fixed #2 - Public test-only cache invalidation API (Medium)

**File:** `hospital-core/src/main/java/com/example/hms/security/tenant/schema/TenantSchemaLookup.java`

**Copilot:** `invalidateAll()` was documented as test-only but exposed as a
public production method, making accidental runtime cache clearing too easy.

**Resolution:** Fixed. `invalidateAll()` is now package-private, preserving test
access from the same package without exposing it as public API.

---

### Fixed #3 - Flaky TTL-expiry test (Medium)

**File:** `hospital-core/src/test/java/com/example/hms/security/tenant/schema/TenantSchemaLookupTest.java`

**Copilot:** The cache-expiry test used a 1-ns TTL plus spin-waiting, which could
still be flaky on systems with coarse clock resolution.

**Resolution:** Fixed at the root. `TenantSchemaLookup` now accepts an injectable
`Clock` through its package-private test constructor while production continues
to use `Clock.systemUTC()`. The TTL test uses a small mutable test clock and
verifies exactly two DB lookups after advancing past the TTL.

---

### Fixed #4 - Stale schema-tenancy test counts (Low)

**Files:**

- `docs/runbooks/schema-per-tenant-migration.md`
- `docs/roadmap.md`
- `docs/roadmap.csv`

**Copilot:** The runbook and roadmap said 19 tests, but the PR already had 24.

**Resolution:** Fixed and kept current after adding regression tests for the
connection-leak and search-path SQL-safety fixes. The docs now state 28 tests
across the schema-tenancy resolver, provider, and lookup classes.

---

### Net result (PR #329)

| Severity | Count | Status |
| --- | --- | --- |
| High | 1 | Fixed |
| Medium | 2 | Fixed |
| Low | 1 | Fixed |

Files touched in the follow-up:

- `hospital-core/src/main/java/com/example/hms/security/tenant/schema/SchemaTenantConnectionProvider.java`
- `hospital-core/src/main/java/com/example/hms/security/tenant/schema/TenantSchemaLookup.java`
- `hospital-core/src/test/java/com/example/hms/security/tenant/schema/SchemaTenantConnectionProviderTest.java`
- `hospital-core/src/test/java/com/example/hms/security/tenant/schema/TenantSchemaLookupTest.java`
- `docs/runbooks/schema-per-tenant-migration.md`
- `docs/roadmap.md`
- `docs/roadmap.csv`
- `docs/copilot-review.md`

---

## Copilot review — PR `chore/roadmap-archive-deferred-batch` (2026-05-17)

### Fixed #5 — "Six rows left" wording inconsistency (Medium)

**File:** `docs/roadmap.md`

**Copilot:** The 2026-05-17 archive blockquote opened with "Six rows
left on the active backlog after this archive" but then listed 22
rows in the parenthetical (11 `not-started` + 11 `started`). The
"Six" was a stale carry-over from the count of rows being archived,
not the count remaining.

**Resolution:** Fixed across two commits.

1. Commit [`45f78015`](https://github.com/DevFaso/hms/commit/45f78015)
   reworded to "22 rows — 11 `not-started` … and 11 `started` on
   foundation passes with named follow-on" and added a clarifying
   sentence on the foundation-pass discipline (`started ≠
   completed`).
2. Commit [`638123b1`](https://github.com/DevFaso/hms/commit/638123b1)
   (merge-resolution) refreshed the snapshot again after PRs
   #349-#352 landed on main and flipped the 11 `not-started` rows
   to `started`. The current text reads: "22 rows all at `started`
   (no row remains `not-started` …)".

### Fixed #6 — Row 31 clustering rationale (Low)

**File:** `docs/roadmap.md`

**Copilot:** The mobile-cluster prose said "Row 30 was already
gated on 28/29 mobile parity in the original dependency graph, so
it moves with them" but didn't explain why row 31 was clustered
with 28-30. Row 31 (Mobile test coverage uplift) has no listed
dependency in the CSV — it's grouped for shared team/tooling
reasons, not as a hard dependency-graph gate.

**Resolution:** Fixed. Prose now distinguishes the two cases:
"Row 30 has a hard CSV dependency on rows 28/29 (`Mobile parity`)
and moves with them. Row 31 has no listed dependency — it's
clustered for shared team / tooling efficiency, not a
dependency-graph gate."

### Net result (PR `chore/roadmap-archive-deferred-batch`)

| Severity | Count | Status |
| --- | --- | --- |
| Medium | 1 | Fixed |
| Low | 1 | Fixed |

Files touched in the follow-up:

- `docs/roadmap.md`
- `docs/copilot-review.md`

---

## Copilot review — PR #356 `feat/v2.0-schema-per-tenant-scripts` (2026-05-17)

### Fixed #7 — PGUSER not validated against SAFE_IDENTIFIER regex (Medium)

**File:** `scripts/tenancy/provision-schema.sh`

**Copilot:** The script interpolates `PGUSER` into SQL identifiers
(`CREATE SCHEMA … AUTHORIZATION "${PGUSER}"` and
`ALTER DEFAULT PRIVILEGES FOR ROLE "${PGUSER}"`) but `PGUSER` wasn't
validated against `SAFE_REGEX` even though the comment claimed all
identifiers were pre-validated. Could break the SQL or, worst case,
allow identifier injection if `PGUSER` contained quotes.

**Resolution:** Fixed. Added an explicit `[[ "${PGUSER}" =~ ${SAFE_REGEX} ]]`
check right after the `PGUSER` env-var assertion, with the same
"fail-fast on regex mismatch" pattern used for `SCHEMA_NAME` and
`HMS_APP_ROLE`. The HMS deployment convention is lowercase
snake_case roles (`hms_app`, `hms_liquibase`) so the existing strict
allowlist applies cleanly to `PGUSER` too.

### Fixed #8 — invalidate-tenant-cache.sh URL missing /api context path (High)

**File:** `scripts/tenancy/invalidate-tenant-cache.sh`

**Copilot:** The script built `${HMS_BACKEND_BASE_URL}/super-admin/...`
but the backend is served under `server.servlet.context-path=/api`,
and the runbook documents the endpoint as `POST /api/super-admin/...`.
The script would always 404 unless operators happened to include
`/api` in `HMS_BACKEND_BASE_URL` manually.

**Resolution:** Fixed. The URL builder now normalises the base URL
(strips trailing `/`, strips trailing `/api` if already present) and
explicitly appends `/api/super-admin/tenancy/...`. The
`HMS_BACKEND_BASE_URL` doc-string was updated to document that the
script accepts both forms (with or without `/api`). Idempotent: an
operator who already has `/api` in their env var still gets the
correct single-`/api` URL.

### Fixed #9 — copy-rows.sh src-count drift after commit (High)

**File:** `scripts/tenancy/copy-rows.sh`

**Copilot:** Row-count verification ran AFTER the REPEATABLE READ
transaction committed, so the source counts were taken from a fresh
snapshot. Any concurrent writes for the hospital during the copy
window would make `src != dst` and force a false-failure abort even
though the copy itself was correct.

**Resolution:** Two-part fix.

1. The verification now runs **inside** the REPEATABLE READ
   transaction. Each table's INSERT uses a CTE that captures the
   source `count(*)` and the `RETURNING` count in the same snapshot,
   emitting a `tbl|src|copied|status` row that bash parses. Any
   `MISMATCH` aborts before `COMMIT`, so a broken copy never reaches
   the tenant schema.
2. The script now **refuses to run** unless the hospital is in
   `lifecycle_state = 'SUSPENDED'`. This machine-enforces the
   drain-before-copy ordering (see #10 below) so even a careless
   operator can't accidentally copy a live tenant.

### Fixed #10 — runbook step ordering: drain before copy (High)

**File:** `docs/runbooks/schema-per-tenant-migration.md`

**Copilot:** The original runbook had `Step 2 (copy)` → `Step 3
(drain)` → `Step 4 (flip)`. Copying while the hospital was still
`ACTIVE` allowed concurrent writes against the source tables,
which is the root cause of the `src != dst` drift in #9.

**Resolution:** Steps reordered. New flow:
`Step 2 (drain)` → `Step 3 (copy)` → `Step 4 (flip + invalidate)`.
The drain step now leads with a new section header explaining
why the order matters, with an explicit pointer to the
`copy-rows.sh` SUSPENDED-state guard. Step numbers cascaded
through the rest of the runbook.

### Fixed #11 — CI test failure: TenantSchemaCacheControllerIT expected 401/404 but got 403 (CI)

**File:** `hospital-core/src/test/java/com/example/hms/security/tenant/schema/TenantSchemaCacheControllerIT.java`

**Issue:** The IT was modelled after `ChargebackReportControllerIT`
and `DicomProxyControllerIT`, which use GET endpoints and stop at
Spring Security with 401 for unauthenticated requests. This new
endpoint is POST, so the request hits the CSRF filter first and
returns **403** instead. The IT failed CI with
`Expecting 403 to be in [401, 404]`.

**Resolution:** Widened the expected status set to `[401, 403, 404]`
and documented why all three are valid (401 anonymous, 403 CSRF
rejection on POST, 404 authenticated SUPER_ADMIN with flag off).
The DisplayName + Javadoc both call out the POST-vs-GET difference
explicitly so the next foundation-pass IT doesn't repeat the same
assumption.

### Net result (PR #356)

| Severity | Count | Status |
| --- | --- | --- |
| High | 3 | Fixed |
| Medium | 1 | Fixed |
| CI (test) | 1 | Fixed |

Files touched in the follow-up:

- `scripts/tenancy/provision-schema.sh`
- `scripts/tenancy/invalidate-tenant-cache.sh`
- `scripts/tenancy/copy-rows.sh`
- `docs/runbooks/schema-per-tenant-migration.md`
- `hospital-core/src/test/java/com/example/hms/security/tenant/schema/TenantSchemaCacheControllerIT.java`
- `docs/copilot-review.md`

---

## Copilot review — PR #357 `feat/v1.1-kpi-dashboard-follow-on` (2026-05-17)

### Fixed #12 — ESLint `@typescript-eslint/array-type` (7 errors)

**Files:** `kpi-cards.component.ts`, `kpi-sparkline.component.ts`

**Lint:** Seven instances of `Array<T>` triggered
`@typescript-eslint/array-type`. The project convention (enforced by
the Angular `eslint:recommended` ruleset) is `T[]` shorthand.

**Resolution:** All seven sites flipped from `Array<T>` to `T[]`:
the cards `KpiCard.series` field, the sparkline's `input.required<…>`
generic, the `dots` computed, the local `out` array in `dots()`, and
both type annotations on the private `dataPoints` helper (parameter
+ return + the inner `finite` accumulator).

### Fixed #13 — Sparkline aria-label hardcoded English (Medium)

**File:** `hospital-portal/src/app/analytics/kpi-cards/kpi-sparkline.component.ts`

**Copilot:** `ariaLabel` was built with a template string
`` `${this.label()} trend, ${finite.length} data points` `` — the
words "trend" and "data points" were hardcoded English even though
this PR adds translated KPI trend labels in FR/ES. French/Spanish
users would have got mixed-language screen-reader text.

**Resolution:** Sparkline now injects `TranslateService` and resolves
`ANALYTICS.KPI.SPARKLINE_ARIA` with `{{label}}` and `{{count}}`
placeholders. The translation file owns the word order, which matters
because FR/ES put "tendance" / "tendencia" *before* the KPI label,
not after. New keys added in EN/FR/ES:

- EN: `"{{label}} trend, {{count}} data points"`
- FR: `"Tendance {{label}}, {{count}} points de données"`
- ES: `"Tendencia {{label}}, {{count}} puntos de datos"`

### Fixed #14 — SonarQube Critical: duplicate `"fromInclusive"` literal (Code Smell)

**File:** `hospital-core/src/main/java/com/example/hms/service/impl/KpiDashboardServiceImpl.java`

**Sonar:** "Define a constant instead of duplicating this literal
`fromInclusive` 3 times." Same flag fired for `toExclusive`. Both
strings were used as `setParameter(...)` keys in `computeNoShowRate`
and in the no-show branch of `computeTrend`.

**Resolution:** Two new static final constants
(`PARAM_FROM_INCLUSIVE`, `PARAM_TO_EXCLUSIVE`) added next to the
existing `PARAM_HOSPITAL_ID` / `PARAM_WINDOW_START` /
`PARAM_WINDOW_END`. Both literal usages replaced; the named-query
SQL itself still uses `:fromInclusive` / `:toExclusive` since those
are JPA parameter markers, not string literals.

### Fixed #15 — SonarQube Critical: `computeTrend` cognitive complexity 25 > 15 (Code Smell)

**File:** `hospital-core/src/main/java/com/example/hms/service/impl/KpiDashboardServiceImpl.java`

**Sonar:** "Refactor this method to reduce its Cognitive Complexity
from 25 to the 15 allowed." `computeTrend` orchestrated three
sequential native-query+merge blocks plus a final emit loop — each
block by itself was ~6 complexity, summing past the gate.

**Resolution:** Split into orchestrator + four helpers, each well
under 15:

- `addDoorToDoctorTrend(series, hospitalId, windowStart, windowEnd)`
- `addDispenseLeadTimeTrend(series, hospitalId, windowStart, windowEnd)`
- `addNoShowRateTrend(series, hospitalId, fromInclusive, appointmentEndExclusive)`
- `emitTrendPoints(series, fromInclusive, toInclusive)` — static

`computeTrend` itself drops to ~5 lines: initialise the map, call
the three adders, return the emit. Same external contract; same
DTO shape; same per-KPI nan-as-gap semantics.

### Net result (PR #357)

| Severity | Count | Status |
| --- | --- | --- |
| Critical (Sonar code smell) | 2 | Fixed |
| Medium (Copilot) | 1 | Fixed |
| Lint errors | 7 | Fixed |

Files touched in the follow-up:

- `hospital-core/src/main/java/com/example/hms/service/impl/KpiDashboardServiceImpl.java`
- `hospital-portal/src/app/analytics/kpi-cards/kpi-cards.component.ts`
- `hospital-portal/src/app/analytics/kpi-cards/kpi-sparkline.component.ts`
- `hospital-portal/src/assets/i18n/en.json`
- `hospital-portal/src/assets/i18n/fr.json`
- `hospital-portal/src/assets/i18n/es.json`
- `docs/copilot-review.md`
- `.claude/skills/angular-portal-component/SKILL.md`
- `.claude/skills/pr-review-response/SKILL.md`

---

## Copilot review — PR #358 `feat/v1.1-adt-auto-create` (2026-05-17)

### Fixed #16 — Wrong-tenant provider UUID could relocate Admission (High)

**File:** `hospital-core/src/main/java/com/example/hms/service/integration/impl/MllpInboundAdtVisitProjectionServiceImpl.java`

**Copilot:** The auto-create path stamped
`admission.setHospital(provider.getHospital())` from the configured
`admittingProviderId` Staff row without verifying that the provider's
hospital matched the receiving hospital. Because
`adt_intake_provider_configs` deliberately stores raw UUIDs (no FK
to `hospital.staff`), a misconfigured row could point at a Staff
member belonging to another tenant — an ADT for hospital A would
then create an Admission under hospital B despite the cross-tenant
patient-registration check having been performed for hospital A.

**Resolution:** Extracted a `resolveProvider(config, hospitalId)`
helper that performs both lookups: row existence AND
`provider.getHospital().getId().equals(hospitalId)`. Either failure
returns `Optional.empty()` with a precise log line and the
auto-create branch bails out. The `ProjectionContext` now carries
the live `receivingHospital` reference (in addition to the UUID),
and `buildAdmission` stamps that directly — never the indirected
`provider.getHospital()` — so even an invariant violation in the
provider data can't relocate the row. New unit test
`autoCreateRejectedOnWrongTenantProvider` pins the contract.

### Fixed #17 — Wrong-tenant department UUID could attach (High)

**File:** Same as #16.

**Copilot:** Same class of bug for the optional `departmentId`:
dereferenced by ID only, then attached to the Admission without
checking `department.getHospital().getId() == hospitalId`. A stale
or wrong UUID could attach a department from another hospital.

**Resolution:** Symmetric fix — new `resolveDepartment(config,
hospitalId)` helper that performs the same hospital-match check,
returning `Optional.empty()` on mismatch or missing row. The
caller distinguishes "no department configured" (proceed) from
"configured but unresolvable" (skip) by checking
`config.getDepartmentId() != null`. New unit test
`autoCreateRejectedOnWrongTenantDepartment` pins the contract.

### Fixed #18 — Runbook listed invalid AcuityLevel enum values (Medium)

**File:** `docs/runbooks/hl7-adt-conflict-resolution.md`

**Copilot:** The per-hospital `INSERT` example listed
`LEVEL_3_HIGH` and `LEVEL_4_CRITICAL`, but the
`AcuityLevel` enum's actual values are `LEVEL_3_MAJOR`,
`LEVEL_4_SEVERE`, and `LEVEL_5_CRITICAL`. Operators copy-pasting the
example would write rows that fail enum mapping when the config is
read.

**Resolution:** Corrected the comment to match the actual enum:
`LEVEL_1_MINIMAL, LEVEL_2_MODERATE, LEVEL_3_MAJOR, LEVEL_4_SEVERE,
LEVEL_5_CRITICAL`. Lesson logged: enum-value strings in runbook
examples should be verified against the enum class itself, not
guessed — there's no compile-time check on a documentation string.

### Fixed #19 — Auto-created Admission saved as PENDING, not ACTIVE (Medium)

**File:** Same as #16.

**Copilot:** ADT^A01 is an admit-notification — the patient is
already physically present at the sending facility. The
auto-created `Admission` was saved as `AdmissionStatus.PENDING`,
whose enum documentation says it represents pre-registration. The
in-app `admitPatient` flow uses `ACTIVE`; ADT-created admissions
would be misclassified and excluded from active-admission
workflows.

**Resolution:** Changed the status stamp to
`AdmissionStatus.ACTIVE` with an in-line comment naming A01's
semantics so the next reader doesn't revert the change. Updated
the happy-path test assertion (now `isEqualTo(ACTIVE)` with the
explanatory comment inline).

### Net result (PR #358)

| Severity | Count | Status |
| --- | --- | --- |
| High | 2 | Fixed |
| Medium | 2 | Fixed |

Files touched in the follow-up:

- `hospital-core/src/main/java/com/example/hms/service/integration/impl/MllpInboundAdtVisitProjectionServiceImpl.java`
- `hospital-core/src/test/java/com/example/hms/service/integration/MllpInboundAdtVisitProjectionServiceImplTest.java`
- `docs/runbooks/hl7-adt-conflict-resolution.md`
- `docs/copilot-review.md`

---

## Copilot review — PR A04 (`feat/v1.1-adt-auto-create-encounter`) round 1 (2026-05-17)

### Fixed #20 — SonarQube Quality Gate: 4.7% duplication on new code (> 3% gate)

**File:** `hospital-core/src/main/java/com/example/hms/service/integration/impl/MllpInboundAdtVisitProjectionServiceImpl.java`

**Sonar:** "4.7% Duplication on New Code (required ≤ 3%)". The
projection service file alone was at 9.2% (16 duplicated lines) —
SonarQube quantifies the gate failure per file as well as the
overall %, so the cluster came from one place.

**Root cause:** `tryAutoCreateAdmission` and `tryAutoCreateEncounter`
each contained the same ~10-line gate-and-resolve preamble:

```java
if (!properties.getAutoCreate().isEnabled()) return Optional.empty();
// (different trigger-event check per method)
Optional<AdtIntakeProviderConfig> configOpt =
    intakeConfigRepository.findByHospital_IdAndEnabledTrue(ctx.hospitalId);
if (configOpt.isEmpty()) return Optional.empty();
AdtIntakeProviderConfig config = configOpt.get();
if (!registrationRepository.isPatientRegisteredInHospitalFixed(
    ctx.patient.getId(), ctx.hospitalId)) {
    log.warn(...); return Optional.empty();
}
Optional<Staff> providerOpt = resolveProvider(config, ctx.hospitalId);
if (providerOpt.isEmpty()) return Optional.empty();
Optional<Department> departmentOpt = resolveDepartment(config, ctx.hospitalId);
if (departmentOpt.isEmpty() && config.getDepartmentId() != null) {
    return Optional.empty();
}
```

The two methods diverged only on the trigger-event check and on the
type-specific write path.

**Resolution:** Extracted a `resolveAutoCreateContext(ctx)` helper
that does the full shared gate stack and returns an
`Optional<AutoCreateContext>` carrying the resolved `(config,
provider, department)` tuple. Both type-specific methods now start
with their trigger-event check, call the shared resolver, and then
do their own write path:

```java
private Optional<VisitProjectionResult> tryAutoCreateAdmission(ProjectionContext ctx) {
    if (!TRIGGER_A01.equalsIgnoreCase(ctx.parsed.triggerEvent())) return Optional.empty();
    Optional<AutoCreateContext> resolved = resolveAutoCreateContext(ctx);
    if (resolved.isEmpty()) return Optional.empty();
    AutoCreateContext ac = resolved.get();
    Admission admission = buildAdmission(ctx, ac.config(), ac.provider(), ac.department());
    // ... save + audit + log
}
```

`AutoCreateContext` is a private inner record. `tryAutoCreateEncounter`
follows the same shape plus its own A04-specific gate (assignment-id
non-null + assignment resolve).

**Trade-off:** the A04 "no default_assignment_id" early-exit moved
from before the registration/staff/dept lookups to after them.
Three extra DB calls per misconfigured A04 message; the ADT path
processes a handful of messages per second so this isn't a hot
loop. The
`a04SkippedWhenAssignmentIdMissing` test dropped its
`verifyNoInteractions(registrationRepository/staffRepository)`
ordering assertions — the load-bearing assertions
(`verifyNoInteractions(assignmentRepository)`, no
`encounterRepository.save`, no audit) still hold.

### Fixed #21 — Mockito Optional-default in a04ReachesEncounterBranch... test (High)

**File:** `hospital-core/src/test/java/com/example/hms/service/integration/MllpInboundAdtVisitProjectionServiceImplTest.java`

**Copilot:** The test relied on Mockito's default return for an
unstubbed `Optional`-returning repository method. Copilot's
comment asserted Mockito returns `null` for `Optional`; the test
actually passed because Mockito 2.x+ returns `Optional.empty()`
via `RETURNS_DEFAULTS → ReturnsEmptyValues`. But relying on the
default is fragile — a future Mockito strictness change or a Spy
default switch could break it silently.

**Resolution:** Added an explicit `when(intakeConfigRepository
.findByHospital_IdAndEnabledTrue(eq(hospital.getId())))
.thenReturn(Optional.empty())` stub and a matching
`verify(intakeConfigRepository).findByHospital_IdAndEnabledTrue(...)`
so the contract is now pinned at both ends. Comment in the test
calls out why explicit stubbing matters here even though the
implicit default happens to work today.

### Net result (PR A04 round 1)

| Severity | Count | Status |
| --- | --- | --- |
| Sonar Quality Gate (duplication) | 1 | Fixed |
| Copilot (High) | 1 | Fixed |

Files touched in the follow-up:

- `hospital-core/src/main/java/com/example/hms/service/integration/impl/MllpInboundAdtVisitProjectionServiceImpl.java`
- `hospital-core/src/test/java/com/example/hms/service/integration/MllpInboundAdtVisitProjectionServiceImplTest.java`
- `.claude/skills/pr-review-response/SKILL.md`
- `docs/copilot-review.md`
