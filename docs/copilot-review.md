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
