# Copilot review archive

## 2026-05-03 — `feature/super-admin-mvp-c2-backend` (MVP-c2 backend)

Four Copilot findings on the MVP-c2 backend PR (closing MVP-8c
cross-source aggregation + MVP-9c routing scaffold). All addressed
in a follow-up commit on the same branch before merging.

### 1. Remote provisioning sent the unnormalized request — **High**

> `SuperAdminOrganizationProvisioningServiceImpl` lines 56-61 —
> Remote provisioning delegates the original request DTO, but local
> provisioning normalizes the org code (trim + uppercase) and applies
> default type/region before proceeding. This can lead to the remote
> deployment receiving an unnormalized code (e.g. "eu-tenant") that
> differs from the uniqueness check ("EU-TENANT") and from what local
> provisioning would persist, creating inconsistent behavior across
> deployments. Consider delegating a normalized copy of the request
> (at least code, and any other defaults you apply locally) so both
> paths apply identical normalization rules.

**Fix.** Extracted `normalizeRequest(request)` that builds a copy of
the DTO with the code trimmed + uppercased, type defaulted to
`HEALTHCARE_NETWORK`, region defaulted to `BF`, and contactPhone
trimmed-to-null. The normalized request is computed before the
routing decision, the uniqueness check uses its code, and both the
local builder and `tenantProvisioningClient.provisionRemote(...)`
consume the same normalized DTO. Local-side fields previously read
off `request` (`name`, `notes`, `timezone`, `contactEmail`) now read
off `normalizedRequest` so a future change to normalization
automatically applies to both branches.

**Test.** `createOrganization_remotePathReceivesNormalizedRequest`
captures the DTO sent to the client and asserts code uppercased,
type defaulted, blank phone normalized to null.

### 2. Pagination math could overflow int — **Major**

> `SuperAdminAuditAggregationServiceImpl` lines 68-72 — Pagination
> math uses `int offset = pageNumber * pageSize` and `offset + pageSize`
> for `perSourceLimit`. Because page/size are user-controlled request
> params, this can overflow int (becoming negative) and produce
> incorrect limits/slicing. Use long for offset arithmetic and clamp
> pageSize (and/or pageNumber) to a sane maximum consistent with
> `PER_SOURCE_HARD_CAP`.

**Fix.** Introduced `MAX_PAGE_SIZE = PER_SOURCE_HARD_CAP` (5 000)
and clamped pageSize through `Math.max(1, Math.min(getPageSize(),
MAX_PAGE_SIZE))` before any arithmetic. Offset computed as
`(long) pageNumber * pageSize`; per-source limit is
`(int) Math.min(offset + pageSize, (long) PER_SOURCE_HARD_CAP)`;
the slice indices use `(int) Math.min(offset, (long) merged.size())`.
With the clamp, `offset` is bounded by `pageNumber * 5 000`, which
fits comfortably in an int even for large pageNumber, but the
intermediate values stay long so future constant changes don't
silently regress.

**Test.** `searchAggregated_pageSizeOverMaxIsClamped` requests
`pageSize = Integer.MAX_VALUE` and asserts the response reports
`pageSize = 5 000`.

### 3. Pagination metadata vs. retrievable rows mismatch — **Major**

> `SuperAdminAuditAggregationServiceImpl` lines 70-95 — The hard
> cap (`PER_SOURCE_HARD_CAP`) can make pagination internally
> inconsistent: for deep pages where `offset + pageSize` exceeds the
> cap, the service will never fetch enough rows to fill that page,
> but `totalElements`/`totalPages` are still computed from full
> per-source `COUNT(*)`. Clients may see many pages available but
> get empty results past the cap.

**Fix.** Each per-source count contribution is now
`Math.min(sourceCount, (long) PER_SOURCE_HARD_CAP)` so the response
reports only the rows the service can actually retrieve. A frontend
paginating past page `(cap / pageSize)` will see the page count
end there instead of advertising a phantom 12-of-50.

**Test.** `searchAggregated_capsTotalElementsAtPerSourceHardCap`
stubs the FRONTEND repo to return `count = 100 000` and asserts
the response reports `totalElements = 5 000` and
`totalPages = 250` (= 5 000 / 20).

### 4. DTO Javadoc claimed timestamp+summary non-optional — **Minor**

> `AggregatedAuditEventDTO` lines 15-18 — Javadoc says timestamp
> and summary are non-optional, but the aggregation mappers/tests
> explicitly allow null timestamps (e.g. PermissionMatrix rows with
> `createdAt == null`) and summaries can also be null if underlying
> descriptions are null.

**Fix.** Updated the Javadoc to clarify only `source` and `id` are
guaranteed non-null; every other field is best-effort from the
underlying entity and can be null when the source row is missing
that data.

### Verification

- `:hospital-core:test` — full suite green
- `:hospital-core:jacocoTestCoverageVerification` — 80% INSTRUCTION
  gate green
- Per-class coverage on the touched classes:

  | Class | Instr | Branch |
  | --- | --- | --- |
  | `SuperAdminAuditAggregationServiceImpl` | 100.0% | 100.0% |
  | `SuperAdminOrganizationProvisioningServiceImpl` | 97.4% | 91.7% |
