# Copilot review - PR #329 `feat/v2.0-schema-per-tenant` (2026-05-15)

Working notes for the Copilot review on PR #329. Each item is summarized and
marked fixed after the follow-up changes on `feat/v2.0-schema-per-tenant`.

---

## Fixed #1 - JDBC connection leak on search_path failure (High)

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

## Fixed #2 - Public test-only cache invalidation API (Medium)

**File:** `hospital-core/src/main/java/com/example/hms/security/tenant/schema/TenantSchemaLookup.java`

**Copilot:** `invalidateAll()` was documented as test-only but exposed as a
public production method, making accidental runtime cache clearing too easy.

**Resolution:** Fixed. `invalidateAll()` is now package-private, preserving test
access from the same package without exposing it as public API.

---

## Fixed #3 - Flaky TTL-expiry test (Medium)

**File:** `hospital-core/src/test/java/com/example/hms/security/tenant/schema/TenantSchemaLookupTest.java`

**Copilot:** The cache-expiry test used a 1-ns TTL plus spin-waiting, which could
still be flaky on systems with coarse clock resolution.

**Resolution:** Fixed at the root. `TenantSchemaLookup` now accepts an injectable
`Clock` through its package-private test constructor while production continues
to use `Clock.systemUTC()`. The TTL test uses a small mutable test clock and
verifies exactly two DB lookups after advancing past the TTL.

---

## Fixed #4 - Stale schema-tenancy test counts (Low)

**Files:**

- `docs/runbooks/schema-per-tenant-migration.md`
- `docs/roadmap.md`
- `docs/roadmap.csv`

**Copilot:** The runbook and roadmap said 19 tests, but the PR already had 24.

**Resolution:** Fixed and kept current after adding regression tests for the
connection-leak and search-path SQL-safety fixes. The docs now state 28 tests
across the schema-tenancy resolver, provider, and lookup classes.

---

## Net result

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
docs/roadmap.md
Comment on lines +48 to +50
> Six rows left on the active backlog after this archive (rows 8,
> 18, 19, 21, 22, 25, 36, 39, 41, 42, 44 remain `not-started`; rows
> 20, 23, 24, 26, 27, 32, 33, 35, 37, 38, 43 remain `started`).
Copilot commented 18 minutes ago
Copilot
Copilot
AI
18 minutes ago
Medium
The summary sentence says "Six rows left on the active backlog after this archive" but then lists 11 not-started rows (8, 18, 19, 21, 22, 25, 36, 39, 41, 42, 44) and 11 started rows (20, 23, 24, 26, 27, 32, 33, 35, 37, 38, 43) — 22 rows in total, not six. The "Six" appears to be a stale carry-over from the count of rows being archived in this batch. Either the leading number or the parenthetical inventory is wrong and the two will mislead readers about how many items are still pickable.

Suggested changeset 1 (1)
docs/roadmap.md
Original file line number	Diff line number	Diff line change
 >   start until v0 lands and soaks. Revisit once EMPI v0 is
 >   `completed`, not just `started`.
 >
 > Six rows left on the active backlog after this archive (rows 8,
 > 18, 19, 21, 22, 25, 36, 39, 41, 42, 44 remain `not-started`; rows
 > 20, 23, 24, 26, 27, 32, 33, 35, 37, 38, 43 remain `started`).
 > Twenty-two rows left on the active backlog after this archive
 > (rows 8, 18, 19, 21, 22, 25, 36, 39, 41, 42, 44 remain
 > `not-started`; rows 20, 23, 24, 26, 27, 32, 33, 35, 37, 38, 43
 > remain `started`).
 > **2026-05-16 update — daytime foundation passes flip rows 20, 27,
 > 32, and 43 to `started`.** Four feature branches merged into
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
docs/roadmap.md
>   down on v1.1 today; the four rows naturally cluster as the next
>   batch once one picks them up. Row 30 was already gated on
>   28/29 mobile parity in the original dependency graph, so it
>   moves with them.
Copilot commented 18 minutes ago
Copilot
Copilot
AI
18 minutes ago
Low
The narrative bullet for row 30 in the markdown (lines 30-32) says "Row 30 was already gated on 28/29 mobile parity in the original dependency graph", but the CSV row for 30 (Push notifications for lab results) shows the dependency column as Mobile parity (unchanged before/after). However the bullet under row 28 covers "Patient mobile parity (Android)" which is row 28 — fine. The numbering inside the prose ("Rows 28, 29, 30, 31") matches the CSV, but the row that is the "Mobile test coverage uplift" is row 31 while the dependency-graph statement attributes the gating to row 30. Double-check: it's actually row 30 that has the Mobile parity dependency (correct), so OK — but the prose claim about "the four rows naturally cluster" pairs with row 31 having no listed dependency in the CSV. Consider clarifying that row 31's clustering rationale is team/tooling, not a hard dependency, to avoid implying the dependency graph itself gates row 31.

Suggested changeset 1 (1)
docs/roadmap.md
Original file line number	Diff line number	Diff line change
 >   down on v1.1 today; the four rows naturally cluster as the next
 >   batch once one picks them up. Row 30 was already gated on
 >   28/29 mobile parity in the original dependency graph, so it
 >   moves with them.
 >   moves with them; row 31 is grouped with the cluster for shared
 >   team/tooling efficiency rather than as a hard dependency-graph
 >   gate.
 > - **Row 34 (Tenant onboarding pipeline)** — horizon `v2.0 → oos`.
 >   Depends on row 33 (`Schema-per-tenant migration path`, currently
 >   `started` after its foundation pass) being substantially further

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
