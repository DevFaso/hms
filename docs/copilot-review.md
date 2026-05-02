# PR #202 — Copilot review archive

**Branch:** `feature/p1-12-followup-eligibility-notes-smartphrase`

Copilot raised ten review comments on the initial commit. Every one was
addressed in the fixup commit on this branch; this file is the durable record
of what was flagged and how each comment was resolved, mirroring the convention
PR #203 established.

## Resolved

### 1. `EligibilityServiceImpl#submit` — non-deterministic provider resolution

> `providers.stream().findFirst()` on the injected `List<EligibilityProvider>`
> isn't ordered unless beans implement `Ordered` / `@Order`. With
> `StubEligibilityProvider.supports(...) == true` for every scheme, the stub
> can be selected ahead of a real scheme-specific provider depending on bean
> order.

**Fix:** added `@Order(Ordered.LOWEST_PRECEDENCE)` to `StubEligibilityProvider`.
Spring's `List<T>` injection respects `@Order`, so when a real partner-API
connector is registered (P1 #12 follow-up #4), it sits ahead of the stub for
its scheme; the service's `findFirst` resolves deterministically.

### 2. `EligibilityCheckRequestDTO.checkType` — validated before controller forces it

> `@NotNull` on `checkType` runs at `@Valid` time, but the controller sets it
> server-side after validation — so a caller omitting the field still 400s.

**Fix:** dropped `@NotNull` on `checkType`. The `/check` and `/prior-auth`
endpoints continue to overwrite the field server-side; the Schema doc is
updated to call this out.

### 3 + 10. SmartPhrase scope authorization

> `create` / `update` / `delete` are gated only by clinician roles, but the
> request body controls scope, hospitalId, and ownerUserId. Any clinician can
> create GLOBAL macros, hospital-wide macros for arbitrary hospitals, or USER
> macros for other users.

**Fix:** added scope-aware authorization in `SmartPhraseServiceImpl`:

- GLOBAL — only `ROLE_SUPER_ADMIN`.
- HOSPITAL — `ROLE_SUPER_ADMIN`, or `ROLE_HOSPITAL_ADMIN` with an active
  assignment at the target hospital
  (`UserRoleHospitalAssignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode`).
- USER — the request's `ownerUserId` is overridden server-side to the
  authenticated caller (`applyOwnershipDefaults`); update / delete on a
  USER-scope macro further checks `caller.id == existing.owner.id`.

`update` is gated against the EXISTING macro's scope first (so a clinician
can't "rebase" a macro they do not own to a scope they DO control), then
against the requested scope.

### 4. Autocomplete short prefixes

> `autocomplete` will hit the DB for `"."` (the controller default) and return
> the entire visible library.

**Fix:** `MIN_AUTOCOMPLETE_PREFIX = 2` in `SmartPhraseServiceImpl`. The
service short-circuits before any DB call when the prefix is shorter than
two characters or doesn't begin with `.`.

### 5. Autocomplete cross-tenant leak

> The endpoint accepts an arbitrary `hospitalId` and forwards it without
> validating against the caller's allowed hospital scope. A clinician at
> hospital A can query hospital B's HOSPITAL-scope macros if they know the
> UUID.

**Fix:** the controller now resolves the hospital scope through
`ControllerAuthUtils.resolveHospitalScope(...)` before calling the service.
Non-`SUPER_ADMIN` callers cannot query a hospital they don't staff.

### 6. `latestForPatient` / `listByPatient` cross-tenant leak

> Eligibility history is queried purely by `patientId`. Without a hospital
> scope check, a user who knows another patient UUID can read eligibility
> results from any hospital.

**Fix:** added `EligibilityCheckRepository`
`findByPatient_IdAndHospital_IdOrderByRequestedAtDesc` and
`findFirstByPatient_IdAndHospital_IdAndSchemeAndCheckTypeOrderByRequestedAtDesc`.
Service overloads accept `UUID hospitalId`. Controller derives the hospital
scope via `ControllerAuthUtils.resolveHospitalScope(...)` and passes through;
`null` is intentionally allowed for `SUPER_ADMIN`'s unscoped global view.

### 7. USER-scope uniqueness when hospitalId is null

> `findFirstByTriggerIgnoreCaseAndScopeAndHospital_IdAndOwner_Id` with
> `hospital.id = NULL` doesn't match rows where `hospital_id IS NULL` (JPA
> equality semantics). Duplicates pass the guard and trip the DB unique index
> at save time.

**Fix:** added
`findFirstByTriggerIgnoreCaseAndScopeAndHospitalIsNullAndOwner_Id`. The
service branches on `hospitalId == null` and uses the `IsNull` variant, so
the guard catches the conflict before the DB does.

### 8. `LOWER(sp.trigger)` defeats the trigger index

> The autocomplete query wraps `sp.trigger` in `LOWER(...)` even though writes
> normalise to lowercase, preventing PostgreSQL from using the indexed
> `phrase_trigger` btree for prefix lookups.

**Fix:** the JPQL now compares `sp.trigger LIKE CONCAT(:prefix, '%')`
directly. The service already lowercases the prefix on the way in, and
`SmartPhrase#normalize` lowercases the trigger on write — so the comparison
is correct without the wrapper, and the existing index is usable.

### 9. `incrementUsage` doesn't bump `updatedAt`

> Other atomic-counter repos (`BreakGlassSessionRepository.incrementAuditCount`)
> also bump `updatedAt`; leaving it stale is confusing for "recently updated"
> views and audit.

**Fix:** the JPQL `UPDATE` now sets `sp.updatedAt = :ts` alongside
`sp.usageCount` and `sp.lastUsedAt`.

## Out-of-scope follow-ups (not addressed in this PR)

- The Sonar `S6916` "use pattern-match guard" hint on the new authz `switch`
  statements (a stylistic preference, not a correctness issue).
- Real partner-API connectors for NHIS / NHIA / CNAMGS / mutuelle are still
  the deferred `EligibilityProvider` bean SPI integration work — when one
  registers with default (non-`LOWEST_PRECEDENCE`) order, it automatically
  wins for its scheme.
