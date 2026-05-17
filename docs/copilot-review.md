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
