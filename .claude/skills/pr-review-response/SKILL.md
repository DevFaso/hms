---
name: pr-review-response
description: Use when preparing a feature PR commit message, responding to Copilot AI review comments, addressing Sonar code-smell findings, or naming a feature branch. Captures HMS's foundation-pass commit style, branch-naming convention, "started vs completed" status discipline, and the specific lessons learned from recent reviews on PRs #331-#335.
---

# PR + review-response patterns

The team has settled into a specific commit-message + review-response
shape across PRs #331-#335. Follow it for any new PR — reviewers expect
this format.

## Branch naming

- `feat/v<X.Y>-<kebab-slug>` — new feature ranging across one roadmap row.
  Examples: `feat/v1.1-oru-r01-lab-persistence`,
  `feat/v2.0-hipaa-posture`.
- `fix/<scope>-<short-description>` — bug fix. Example:
  `fix/keycloak-railway-healthcheck-path`.
- `chore/<scope>-<description>` — non-feature, non-fix work (docs sync,
  dependency bumps, repo hygiene). Example:
  `chore/roadmap-sync-post-overnight`.
- `docs/<scope>-<description>` — pure documentation. Example:
  `docs/sonar-pr-queue-refresh`.

Always prefix the branch with the kind (`feat/` etc.) so the
filter-by-prefix workflow on the develop branch protections triggers
correctly.

## Foundation-pass vs completion

A feature PR almost always flips its roadmap row from `not-started → started`,
**not** `→ completed`. The "completed" transition happens later, in a
separate PR or batched roadmap-sync, once exit criteria are met (soak
run, auditor sign-off, etc.).

The roadmap CSV cell pattern (see the `roadmap-sync-workflow` skill):

> `<original deliverable>. Foundation pass shipped on <branch>
> (PR #N): <schema> + <key classes> + <feature flag> + <test count>.
> <Follow-on scope explicitly named>.`

## Commit message structure

A feature commit follows this shape:

```
<type>(<scope>): <one-line summary mentioning the roadmap row>

<2-3 sentence "what this is + what was deferred"> paragraph.

Schema (V##, strictly additive):
- <columns + indexes + constraints>

Code:
- <key classes + interfaces + their entry points>

Tests:
- <test class names + what they cover>

Documentation:
- <runbook + gap-doc updates>

Roadmap row <N> stays "started" until <follow-on>.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

Sections are optional when there's no content for them (a pure-docs
PR has no Schema / Tests sections). Keep the body wrapped to ~72
columns for readability in `git log` output.

## Review-response commit pattern

When addressing Copilot or Sonar review comments, the commit is its
own `fix(<scope>):` on the feature branch, structured by severity:

```
fix(<scope>): address Copilot review on PR #N (<row name>)

<short blurb naming the source of the review — Copilot AI / Sonar
Cloud / etc.>

<File1>:<Line> — <severity>:
- <One-sentence root cause>
- <One-sentence fix description>

<File2>:<Line> — <severity>:
- ...

<Off-scope comments that don't apply to this PR — explain why>.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

Common review-comment categories the team has seen:

- **Liquibase changelog missing** — V## SQL file added without
  `changelog.xml` registration. See the `liquibase-migration` skill.
- **Lazy-load on `Hospital.organization`** — dereferencing it outside
  the allowlist transaction throws `LazyInitializationException`. See
  the `hl7-mllp-integration` skill.
- **`integration_id` column overflow** — 120-char limit on
  `clinical.integration_message_event.integration_id`. Truncate at
  the synth point.
- **Encryption-claim overstatement** — the PHI inventory has been
  caught marking columns "encrypted" that lack `@Convert`. Always
  audit against `EncryptedStringConverter` usages.
- **Backlog count mismatch** — P0/P1 list lengths vs the header count.
- **Documented runbook doesn't exist** — referenced file not in repo.
  Mark `*(Pending — P0/P1, target …)*`.
- **AssertJ idiom** — `.isEqualTo(0L)` → `.isZero()`,
  `.isEqualTo(true)` → `.isTrue()`, etc.
- **HashMap initial capacity** — prefer `HashMap.newHashMap(n)` over
  `new HashMap<>(n)` (Java 19+).
- **Repeated association literals / unused helpers** — when touching a
  service with `JpaProxyUtils.safeInit(...)`, promote association names
  that appear 3+ times (for example `"patient"`, `"department"`) to
  private constants near the other local constants, and remove stale
  private helpers in the same pass.

## Off-scope comments — when to defer

Sonar and Copilot will sometimes flag files that are **touched** by
your PR but where the offending lines are pre-existing (not in your
diff). Verify with:

```bash
git diff origin/develop -- <file> | head
```

If the offending lines aren't in your diff, **defer to a separate
`fix(<scope>)` PR** and call it out explicitly in the response commit
message:

> The MLLP/Hl7MessageDispatcher Copilot comments on lazy hospital,
> casing normalization, integration_id length, etc. are noise on this
> PR — verified the diff against `origin/develop` touches X but NOT Y.
> Those issues are real and should be addressed in a follow-on
> `fix(hl7)` PR; tracked separately.

Don't silently expand the PR scope — it makes review harder and
inflates the diff against the original review.

## PR title vs commit subject

PR title matches the most representative commit subject (usually the
foundation commit). When there are multiple commits, the PR title
should:

- Lead with `<type>(<scope>):`
- Name the roadmap row
- Stay under ~72 chars

The PR body **should not duplicate the commit message** — GitHub renders
the commit messages right below. Use the body for:

- Reviewer-relevant rollup ("3 files changed, focus on X.java")
- Test plan checklist (manual smoke steps)
- Risk callouts
- Roadmap row reference + status transition statement

## Pre-commit hook

`.claude/settings.json` registers a pre-commit hook that runs
`hospital-portal` lint + format-check on **every** `git commit`. If
the hook fails:

1. **Fix the underlying issue** — usually `npm run format` cleans it.
2. Re-stage + re-commit. The hook re-runs against the new content.
3. **Never** `git commit --no-verify`. The hook exists because we got
   burned on inconsistent formatting in the past.

## Mandatory branch CI gate (run BEFORE pushing)

Every feature branch MUST pass the same four gates CI runs, **before
the PR is opened** — not after. The cost of fixing format / coverage
locally is seconds; the cost of fixing them via Copilot review +
re-push cycles is hours of reviewer attention. This is the single
highest-leverage discipline on the project.

**Frontend (`hospital-portal/`):**

```bash
cd hospital-portal
npm run format:check   # prettier --check src/**/*.{ts,html,scss,md,json}
npm run lint           # eslint src/**/*.{ts,html}
npm test               # jest unit + spec
```

If `format:check` fails, run `npm run format` to auto-fix, then
re-stage. **Do not** push a branch with an unformatted file — the
frontend-ci.yml workflow will fail and you'll waste a Copilot review
cycle on a stylistic flag-down (this happened on
`feat/v1.1-kpi-dashboard-service` — `kpi-cards.component.ts` was
unformatted and tripped Prettier in CI).

**Backend (`hospital-core/`):**

```bash
./gradlew :hospital-core:test :hospital-core:jacocoTestReport :hospital-core:jacocoTestCoverageVerification
```

`jacocoTestCoverageVerification` enforces ≥ 80 % `INSTRUCTION`
coverage on the application classes — the threshold is hard-coded in
`build.gradle` (`limit { counter = 'INSTRUCTION'; minimum = 0.80 }`)
and the task fails the build if the new code drops the ratio.
Exclusions live in the same file (`**/bootstrap/**`, `**/seed/**`,
`**/config/**`, `**/model/**`, `**/payload/**`, `**/exception/**`,
`**/enums/**`, `**/mapper/**`, …) — when adding a class outside those
packages, you must back it with tests.

If `:hospital-core:test` fails locally, CI will fail the same way —
the recent foundation-pass batch had failing tests in three of the
four feature branches (PRs #338, #341, #343) because contributors
relied on `compileJava` passing rather than the full test task.
**Compile success is not enough; the full test task is the gate.**

**Quick recipe before every commit on a feature branch:**

```bash
# From repo root:
(cd hospital-portal && npm run format:check && npm run lint && npm test) \
  && ./gradlew :hospital-core:test :hospital-core:jacocoTestCoverageVerification
```

When that command exits 0, commit. When it doesn't, fix and re-run —
do not skip past a failure.

## Anti-patterns surfaced by Copilot review (2026-05-16 batch)

These caused real review comments on the four daytime foundation-pass
PRs (#338, #341, #342, #343). Each is captured in the relevant domain
skill — this list is for cross-cutting muscle memory.

- **Cross-branch broken doc links.** A `chore/roadmap-sync` PR cannot
  reference runbooks that live only on a parallel feature branch —
  Copilot will flag the link as broken on the sync PR's diff base.
  Either land the runbooks first (squash-merge the feature branches),
  or open the sync PR against a branch that already includes them.
  See PR #339.
- **Feature flag short-circuit ordering.** Provider methods that
  validate request shape (body, id consistency) before calling the
  feature-gated service will return 422 even when the flag is off —
  contradicting the "flag-off ⇒ 405" contract and breaking the
  flag-off ITs. Always
  `if (!writeService.isEnabled()) throw MethodNotAllowedException`
  at the very top of every gated handler. See PR #343.
- **Global CORS instead of path-scoped.** Adding sandbox / partner
  origins to the global `CorsConfigurationSource` widens cross-origin
  access for every PHI-bearing endpoint, not just the one you
  intended. Register a path-scoped `CorsConfiguration` (e.g. for
  `/cds-services/**` only) when the new origins are endpoint-specific.
  See PR #338.
- **`@Value` defaults vs env override = replace, not append.** When an
  operator sets `APP_SOMETHING=value`, Spring replaces the entire
  default list — it does not extend. Runbook and code comments that
  claim "add" or "extend" are wrong; either rewrite the prose to say
  "replace" or compose defaults in code so additions truly append.
  See PR #338.
- **Tests that only check key presence.**
  `assertThat(json.has("vitals")).isTrue()` passes even if the FHIR
  template lost `{{context.patientId}}` or the `_count=2000` filter.
  When the wire shape IS the contract, assert the values, not just
  the keys. See PR #338.
- **Audit `entityType` capitalization.** `AuditEventLogServiceImpl`
  special-cases `entityType` matching `"PATIENT"` (case-insensitive
  against that literal), so passing `"Patient"` (Pascal case)
  silently disables the patient-resolution path. Use the same literal
  the rest of the codebase uses — `"PATIENT"`. See PR #343.
- **`mrn <> ''` doesn't exclude whitespace.** Partial unique index
  predicates that want to skip blank values must `btrim(mrn) <> ''`
  (or equivalent) — `<> ''` still includes `'   '`. See PR #343.
- **Catch-all RxJS error swallow.**
  `pipe(catchError(() => of({} as T)))` in a frontend service renders
  401 / 403 / 500 as "no data" cards, hiding outages from operators.
  Either let the error propagate to the component or return a typed
  error state. See PR #341.
- **Component reachability mismatch.** Embedding a component inside a
  route guarded by `ROLE_SUPER_ADMIN` while the backing endpoint is
  also exposed to `HOSPITAL_ADMIN / DOCTOR / NURSE / STAFF` makes the
  new UI unreachable for its real users. Place the component on a
  route whose guard matches the backend's `@PreAuthorize`. See PR #341.
- **Date-window cap off-by-one.** For an inclusive `[from, to]` window,
  `from.plusDays(N).isBefore(to)` allows `N+1` days. Use
  `from.plusDays(N - 1).isBefore(to)` if you mean "at most N inclusive
  days". See PR #341.
- **Alert runbook anchors that don't exist.** A `runbook_url` fragment
  like `#hmssyntheticprobefailurerate` must match a heading in the
  linked runbook; otherwise responders land on the top of the page
  instead of on alert-specific guidance. Either add the anchor or drop
  the fragment. See PR #342.
- **Wide-open exporter port.** `9115:9115` (or any `:port:port`
  binding) on a docker-compose service exposes that port on every host
  interface — for the blackbox exporter specifically, that's an SSRF /
  scanning primitive on the LAN. Use `127.0.0.1:9115:9115` for
  local-only ports. See PR #342.
- **Hard-coded credentials in runbooks.** Even local-smoke `username` /
  `password` literals get copied into shared environments. Use env
  placeholders (`$env:HMS_SMOKE_PASSWORD`) instead. See PR #341.

## Anti-patterns surfaced by Copilot + Sonar (2026-05-16 evening batch — PRs #349 / #350 / #351 / #352)

Second wave of review findings across the four follow-on PRs that
landed rows 8 / 18 / 19 / 20-follow-on / 21 / 22 / 25 / 36 / 39 / 41 /
42 / 44. Each lesson is also captured in its domain skill — this
list is cross-cutting muscle memory.

- **Spring env-var binding requires an explicit `${ENV:default}`
  placeholder in `application.properties`.** Runbooks that say
  "set `FHIR_BULK_EXPORT_ENABLED=true`" or "set `DICOM_PROXY_ENABLED=true`"
  do NOT work unless `application.properties` carries
  `app.fhir.operations.bulk-export.enabled=${FHIR_BULK_EXPORT_ENABLED:false}`
  (and one line per flag). Spring's relaxed binding would otherwise
  expect `APP_FHIR_OPERATIONS_BULK_EXPORT_ENABLED` — the prose-friendly
  short var name has no path to the property without the explicit
  placeholder. **Always either ship the placeholder line in
  `application.properties` when you author the runbook, or write the
  runbook against the canonical `APP_*` var name.** Caught on the
  EMPI / Async-Kafka / DICOM-proxy / tenant-cost runbooks (PRs #349,
  #352).
- **`patientRepository.findById(uuid)` is NOT tenant-aware.** The
  base `JpaRepository` returns any patient by primary key regardless
  of `HospitalContextHolder`. Code that reads Patient directly +
  immediately renders it (Bundle entry, mapper, etc.) leaks PHI
  (name, DOB, address, phone, email) across tenants. Always gate
  Patient resolution behind
  `PatientHospitalRegistrationRepository.findByPatientIdAndHospitalId(...).isPresent()`
  and return 404 otherwise. Caught on `PatientEverythingService` in
  PR #351; the assumption that "`findById` is tenant-aware" was
  recorded incorrectly in earlier skills notes.
- **Cross-tenant guard must DENY on null/empty active hospital
  context, not allow.** A super-admin without an explicit
  `X-Hospital-Id` header has `HospitalContextHolder.getActiveHospitalId()
  == null`. A guard that only rejects when both the stored
  hospitalId AND the current context's hospitalId are non-null lets
  any super-admin call see any tenant's data — the inverse of the
  "invisible cross-tenant rejection" contract. Pattern:
  `if (activeHospitalId == null) return Optional.empty();` first,
  then the equality check. Caught on `FhirBulkExportService.getJob`
  in PR #351.
- **Aggregate queries must group by a stable key, not display
  name.** Hospital names are not unique in the schema (only `code`
  is unique); a rename also splits the same tenant across old/new
  snapshots. Group by `hospital.id` (or `hospital.code`) and project
  `hospital_name` from a JOIN if you need the label. Caught on
  `AuditEventLogRepository.countByHospitalBetween` in PR #352.
- **AuditEventType naming convention: past-tense `_UPDATED`.** The
  "Care delivery workflow" group already uses `APPOINTMENT_UPDATED`,
  `PRESCRIPTION_UPDATED`, `LAB_RESULT_UPDATED`,
  `IMAGING_RESULT_UPDATED`. New event types must follow suit; renaming
  a constant AFTER it has been persisted to audit history is a
  multi-step migration. Caught on `ENCOUNTER_UPDATE` (should have
  been `ENCOUNTER_UPDATED`) in PR #350.
- **USER-actor audits MUST carry the user/assignment context.**
  Building an `AuditEventRequestDTO` with no `userId` /
  `assignment` makes `AuditEventLogServiceImpl` resolve the actor as
  `SYSTEM` — which silently mis-attributes clinician access to "the
  system" instead of the actual user. For any endpoint that
  represents authenticated clinical access (DICOM proxy, FHIR write,
  etc.), pull the principal from `SecurityContextHolder` and attach
  it to the audit DTO. Caught on `DicomProxyService.emitAudit` in
  PR #349.
- **Test `@DisplayName` must match the assertion set.** When the
  assertion accepts `isIn(401, 403, 404)`, the display name must say
  "401, 403, or 404 when flag off" — not "401 or 404". The test
  output is operator-facing and drifting between docstring and
  assertion is a real bug-class (operators read the green dot and
  trust the docstring). Caught on `EmpiProbabilisticControllerIT` +
  `DicomProxyControllerIT` in PR #349.
- **Roadmap cell test-count description must match the actual test
  assertions.** When the IT widens from `isIn(401, 404)` to
  `isIn(401, 403, 404)`, the roadmap cell mentioning "1 IT —
  flag-off 401/404" must update too. Caught on row 25 in PR #349.
- **Javadoc must not promise behavior the implementation lacks.**
  `EmpiProbabilisticProperties` Javadoc said "the foundation pass
  returns ranked candidates for the receptionist UI to review" — but
  `EmpiProbabilisticMatcher` returns an empty list both flag-off
  AND flag-on (scorer body deferred). Doc-promises-empty-impl is a
  worse contract leak than the missing impl itself. Caught on
  `EmpiProbabilisticProperties` in PR #349.
- **Properties-class Javadoc must not reference classes that don't
  exist yet.** `AsyncPipelineProperties` Javadoc said "the foundation
  pass ships only the property + a placeholder consumer wiring
  class" — but no such consumer class exists. Either ship the
  placeholder class (preferred) or rewrite the Javadoc to say "the
  foundation pass ships only the properties; consumer wiring is the
  follow-on". Caught on `AsyncPipelineProperties` in PR #349.
- **`audit_event_logs` lives in the `support` schema, NOT `audit`.**
  `AuditEventLog` is `@Table(schema = "support")`. Runbooks /
  ad-hoc SQL that reference `audit.audit_event_logs` will fail with
  missing-table errors. Same applies to other deceptively-named
  tables — verify against the entity's `@Table(schema = ...)` before
  pasting SQL into a runbook. Caught on
  `per-tenant-cost-observability.md` in PR #352.
- **Backend health endpoint is `/api/actuator/health`, not
  `/actuator/health`.** `server.servlet.context-path=/api` is set
  application-wide. Smoke scripts and preflight harnesses that probe
  the bare path get 404 against the documented base URL. Caught on
  `scripts/keycloak/preflight.sh` in PR #352.
- **Input-validation parsers must 400 on malformed input, not
  silently default.** A controller that does
  `LocalDate.parse(raw)` inside `try { ... } catch { return default; }`
  turns `?from=not-a-date` into a successful report for the wrong
  window. Reject the bad input with `400 Bad Request` (or use
  `@DateTimeFormat` + Spring's binding-failure handler). Caught on
  `ChargebackReportController.parseOrDefault` in PR #352.
- **FHIR bulk-data spec requires 400 + OperationOutcome on
  malformed `_since` / `_outputFormat`.** Silently returning `null`
  from `parseInstant` makes the runner fall back to a full export
  while the client believes the incremental window held —
  observable as duplicate-fanout bills the next quarter. Same
  pattern as the input-validation lesson above, but with the
  spec-required response shape. Caught on
  `FhirBulkExportOperationProvider.parseInstant` in PR #351.
- **Dead-code in operation providers.** Constructing a HAPI
  `Parameters` resource and then writing a hand-rolled JSON string
  for the response body is misleading — at first glance it looks
  like the response is built from the model. Either use HAPI's
  `IParser.encodeResourceToString(params)` or drop the unused
  `Parameters` construction. Caught on
  `FhirBulkExportOperationProvider` in PR #351.
- **Polynomial-backtracking regex in split / match.** A pattern
  like `raw.split("\\s*,\\s*")` is a Sonar finding for ReDoS. For
  comma-separated env input the safe approach is
  `Arrays.stream(raw.split(",")).map(String::trim)
  .filter(s -> !s.isEmpty()).toList()`. Caught on
  `FhirBulkExportOperationProvider.parseTypeList` in PR #351.
- **Flag-off ITs need an authenticated allowlisted case to pin the
  contract.** A test that only sends an unauthenticated request and
  accepts `401/403/404` would still pass if the controller returned
  200 to an authenticated `SUPER_ADMIN` with the flag off — because
  Spring Security blocks the unauth call before the flag is
  reached. To pin the flag-off contract you need (a) the
  unauthenticated 401/403 check AND (b) an authenticated allowlisted
  user that asserts 404. Caught on `DicomProxyController` in PR
  #349; same fix owed on every flag-off IT this batch.
- **Sonar coverage gate fires at 80% of NEW code.** New classes
  with no instruction coverage (`EmpiCandidateMatchDTO` at 0%) or
  thin coverage (29% / 53%) fail the SonarQube quality gate even
  when the global jacoco gate passes. Either back the new class
  with tests OR add the package to the jacoco exclusion list in
  `build.gradle` if it's pure carrier (DTO, properties, config). The
  fhir/empi/imaging/observability packages may need exclusion
  review post-PR-349. Caught on PR #349.
- **`TODO row-N follow-on:` comments are Sonar code-smell
  Info-level.** They surface as `S1135 — Complete the task
  associated to this TODO comment`. They're intentional — the
  comment is the marker for the follow-on PR — but the team may
  prefer `// FOLLOW-ON (row N):` or no comment at all. Caught on
  `EmpiProbabilisticMatcher` + `DicomProxyService` in PR #349.

## Anti-patterns surfaced by Copilot + Sonar (2026-05-17 batch — PRs #356 / #357)

This batch tripped four findings on row-33 (`feat/v2.0-schema-per-tenant-scripts`)
and four on row-32 (`feat/v1.1-kpi-dashboard-follow-on`). Each is
the kind of thing easy to repeat — codify before the next PR.

### Backend — duplicate string literals in `setParameter` calls

**Caught:** PR #357 SonarQube Critical
"Define a constant instead of duplicating this literal `fromInclusive`
3 times." Same flag fired on `toExclusive`. Both were used as JPA
named-parameter keys in `KpiDashboardServiceImpl#computeNoShowRate`
and `#computeTrend`.

**Pattern to follow:** when a class issues 2+ named-parameter JPA
queries that share a param name, extract a `private static final
String PARAM_<NAME> = "<paramName>"` constant near the top of the
class. The pattern is already in use for `PARAM_HOSPITAL_ID` /
`PARAM_WINDOW_START` / `PARAM_WINDOW_END` — add to it rather than
inlining. Sonar's threshold for duplication is **3 occurrences**;
two is fine, three is not. Anywhere you `setParameter("foo", ...)`
in a second method, ask whether a third call is coming and pre-empt
with a constant.

The query SQL itself still uses `:fromInclusive` / `:toExclusive`
as JPA parameter markers — those are not string literals being
counted; only the Java `String` arguments to `setParameter` trip
the smell.

### Backend — long aggregation methods trip Cognitive Complexity 25 > 15

**Caught:** PR #357 SonarQube Critical
"Refactor this method to reduce its Cognitive Complexity from 25
to the 15 allowed." `KpiDashboardServiceImpl#computeTrend`
orchestrated three sequential native-query + per-row merge blocks
plus a final emit loop. Each block was ~6 complexity; the sum
exceeded the gate.

**Pattern to follow:** when a method runs N similar sub-tasks in
sequence (per-table, per-KPI, per-section …), split into an
orchestrator + N narrow helpers from the start. Each helper takes
the shared accumulator (e.g. `Map<LocalDate, double[]> series`)
plus its own parameters and mutates the accumulator. The
orchestrator stays at ~5 lines: initialise → call helpers → emit.

For row 32 the split was:

- `addDoorToDoctorTrend(series, hospitalId, windowStart, windowEnd)`
- `addDispenseLeadTimeTrend(series, hospitalId, windowStart, windowEnd)`
- `addNoShowRateTrend(series, hospitalId, fromInclusive, appointmentEndExclusive)`
- `emitTrendPoints(series, fromInclusive, toInclusive)` — pure, static

Apply the same shape to: per-tenant query loops, per-table copy
scripts (Java equivalent), per-section report builders, any
"compute X timeseries / rollup / summary across N independent
inputs" method.

### Backend — POST controller ITs must accept 401, **403**, and 404

**Caught:** PR #356 CI run on `TenantSchemaCacheControllerIT`. The
IT was modelled on existing GET-endpoint foundation-pass ITs
(`ChargebackReportControllerIT`, `DicomProxyControllerIT`) which
get **401** on anonymous calls and **404** when the feature flag
is off. POST endpoints hit Spring Security's CSRF filter first and
return **403** when no CSRF token is present — the previous
`isIn(401, 404)` assertion failed CI.

**Pattern to follow:** any foundation-pass IT for a POST/PUT/DELETE
endpoint asserts `isIn(401, 403, 404)`. Document the three states
in a comment next to the assertion (401 anonymous, 403 CSRF
rejection on POST, 404 authenticated with flag off). The
DisplayName should call out the POST vs GET difference so the next
copy-paste doesn't repeat the mistake. GET endpoints keep the
`isIn(401, 404)` shape — CSRF doesn't apply.

### Backend — REST scripts MUST include the `/api` context path

**Caught:** PR #356 Copilot review (High) on
`scripts/tenancy/invalidate-tenant-cache.sh`. The script built
`${HMS_BACKEND_BASE_URL}/super-admin/...` but every controller is
mounted under `server.servlet.context-path=/api`
(`hospital-core/src/main/resources/application.properties` line 3),
so the URL always 404'd in practice.

**Pattern to follow:** any operator script (or external integration
doc) that hits the backend MUST append `/api` to the base URL. Use
the normalise-then-append idiom so the script tolerates both base
URLs that include `/api` and those that don't:

```bash
BASE="${HMS_BACKEND_BASE_URL%/}"
BASE="${BASE%/api}"
URL="${BASE}/api/<route>"
```

Document in the env-var doc-string: "with or without the /api
context path — the script normalises both."

### Bash scripts — every identifier in SQL must be regex-validated

**Caught:** PR #356 Copilot review (Medium) on
`scripts/tenancy/provision-schema.sh`. `PGUSER` was interpolated
into `CREATE SCHEMA … AUTHORIZATION "${PGUSER}"` and
`ALTER DEFAULT PRIVILEGES FOR ROLE "${PGUSER}"` without being
validated against `SAFE_REGEX`, while every other identifier
(`SCHEMA_NAME`, `HMS_APP_ROLE`) was.

**Pattern to follow:** every shell variable that gets
double-quoted into a SQL identifier position (`"${VAR}"` inside
`AUTHORIZATION`, `OWNER`, `ROLE`, `SCHEMA`, table/column names)
must pass the project's `SAFE_REGEX = '^[a-z][a-z0-9_]{0,62}$'`
check **before** SQL is assembled. The check is a one-line
defensive guard:

```bash
[[ "${VAR}" =~ ${SAFE_REGEX} ]] || \
    err "VAR='${VAR}' fails SAFE_IDENTIFIER regex"
```

The HMS convention is lowercase snake_case roles/schemas
(`hms_app`, `hms_liquibase`, `tenant_bfq_mil_001`) so the
strict allowlist applies cleanly to every interpolated identifier.
PG technically allows quoted mixed-case, but the stricter regex
matches the application-side
`SchemaTenantConnectionProvider#SAFE_IDENTIFIER`.

### Operational scripts — drain BEFORE copy, machine-enforce the ordering

**Caught:** PR #356 Copilot review (High × 2) on
`scripts/tenancy/copy-rows.sh` + the schema-per-tenant migration
runbook. The original runbook had `copy → drain → flip` ordering;
copying a live tenant let concurrent writes drift the source-row
counts during the copy window, so post-commit `src=dst` verification
false-fails on any in-flight INSERT.

**Pattern to follow:** in any data-cutover procedure, the order is
**drain first, copy second**. Source-row count verification reads
from a stable snapshot once writers are quiesced. The cutover
script should machine-enforce the ordering:

```bash
LIFECYCLE=$(psql -tAc "SELECT lifecycle_state FROM ... WHERE id = '...'")
if [[ "${LIFECYCLE}" != "SUSPENDED" ]]; then
    err "hospital ... is in lifecycle_state='${LIFECYCLE}', not 'SUSPENDED'. Run drain step first."
fi
```

Belt + suspenders: capture the source count **inside** the
REPEATABLE READ transaction (via a `WITH src AS (SELECT count(*) …)`
CTE that runs alongside the INSERT's `RETURNING`) so the count
reflects the snapshot the INSERT read, not a fresh post-commit
read. The runbook narrative leads with **why** the order matters
so the next operator doesn't reorder it back.

### SonarQube — Quality Gate fails at >3% duplication on new code

**Caught:** PR A04 round 1 (`feat/v1.1-adt-auto-create-encounter`)
SonarQube Quality Gate: "4.7% Duplication on New Code (required
≤ 3%)". Single-file drill-down showed
`MllpInboundAdtVisitProjectionServiceImpl` at 9.2% / 16 duplicated
lines — `tryAutoCreateAdmission` and `tryAutoCreateEncounter`
each carried the same ~10-line gate-and-resolve preamble.

**Pattern to follow:** when you ship a foundation pass with a
single "branch" (e.g. A01 Admission auto-create) AND the next
follow-on adds a parallel branch (e.g. A04 Encounter auto-create),
the parallel branch is the natural moment to extract the shared
preamble into a resolver helper. Don't copy-paste-and-modify the
first method to bootstrap the second — that's how the duplication
gate fails on the follow-on PR.

The mechanical recipe:

1. Identify the common preamble — flag checks, repository lookups,
   cross-tenant guards, resolved entities that both branches need.
2. Extract a `resolveX(ctx)` helper returning
   `Optional<ResolvedContext>` where `ResolvedContext` is a private
   inner record carrying the resolved entities.
3. Each branch starts with its own type-specific gate (trigger
   event, message type, etc.), then calls the resolver, then does
   its own write path. Each helper stays under ~15 lines.

**Trade-off to acknowledge in the PR:** moving the shared resolve
in front of any branch-specific early-exit gate costs N extra DB
lookups per "would-have-bailed-early" message. For ADT-class
traffic (a few messages per second) that's fine; for hot loops
(per-request middleware) it's not — that's the dividing line. If
the per-message DB-call count matters, accept the duplication and
add a `// duplication accepted — see PR #N` comment near each
copy so a future reviewer doesn't reflexively dedupe.

**Test-side consequence:** the shared resolve runs more
repositories than the old early-exit path. Tests that asserted
`verifyNoInteractions(<repo>)` on the "branch-specific gate fails"
path will fail after the refactor. Relax those assertions to the
load-bearing ones (no write to the type-specific table, no audit
emission); leave a comment explaining the relaxation references
the duplication-gate refactor so the next reader knows why.

### SonarQube — duplicate-literal threshold counts ANY string at 3 occurrences

**Caught:** PR A04 round 2 Sonar Critical "Define a constant
instead of duplicating this literal `<null>` 3 times" on
`MllpInboundAdtVisitProjectionServiceImpl`. The literal in
question wasn't a SQL parameter name (the row-32 case) — it was a
WARN-log placeholder rendered into three `resolve*` helpers'
cross-tenant guards (`provider.getHospital() == null ? "<null>"
: ...`).

**Pattern to follow:** SonarQube's `S1192` duplicate-literal rule
fires on **any** string repeated 3 times — log placeholders,
error-message fragments, audit-event entity types,
cross-tenant-guard fallbacks, anything. When you write a third
copy-paste of the same string, extract a `private static final
String <SCREAMING_SNAKE>` constant near the other class constants
and give it a one-sentence Javadoc explaining *why* the
placeholder exists, not what it is.

The previous "fromInclusive" lesson (PR #357) framed this as a
JPA-named-parameter concern — generalise it: **any** literal string
with 3 occurrences in new code trips Sonar. Common offenders we've
seen so far: `setParameter` keys, audit-event `entityType` strings,
log-placeholder fallbacks, SQL-fragment chunks. When extracting
helpers/branches that share warn-log shapes (the row-24 `resolve*`
trio is the canonical example), the helpers very often share
placeholder strings too. Audit your warn-log strings at the same
moment you extract the helper.

**Critical-by-default:** the duplicate-literal rule defaults to
Critical severity. It alone won't fail Sonar's Quality Gate (the
gate uses `Reliability_Rating`, `Security_Rating`, etc., not
issue count), but it's visible on every PR diff and contributes
to the "issues" badge. The lesson: a 2-minute-effort fix shouldn't
ship as a Critical on a PR.

### Mockito — `eq(...)` is noise when every argument is `eq(...)`

**Caught:** PR A04 round 2 SonarQube — 16 Minor findings of "Remove
this useless `eq(...)` invocation; pass the values directly" across
the new test methods.

**Pattern to follow:** `eq(...)` is a Mockito *matcher*. Mockito
requires matchers only when at least one argument in the call uses
a matcher — `any()`, `argThat(...)`, `same(...)`, etc. When EVERY
argument would be `eq(<value>)`, just pass the raw values:

```java
// Wrong (16 Minor findings):
when(repo.findByA_AndB_AndC(eq("x"), eq(uuid), eq(true))).thenReturn(...);
verify(repo).findByA(eq(uuid));

// Right:
when(repo.findByA_AndB_AndC("x", uuid, true)).thenReturn(...);
verify(repo).findByA(uuid);

// Still right (mixing matchers — eq() is required here):
when(repo.findByA_AndB_AndC(eq("x"), any(UUID.class), eq(true))).thenReturn(...);
```

**Exception:** `eq(null)` stays. Raw `null` in matcher position is
ambiguous to the stubber (Mockito can't tell whether you mean
"matches null" or "no matcher passed"), so `eq(null)` is the
recommended form for null matchers. When `eq(null)` is mixed with
raw values, every argument needs an explicit matcher form — so the
other args go back to `eq(...)` too. That's why SonarQube doesn't
flag the older `blankSenderProceedsWithNullScope` test's
`eq(null), eq(null), eq("V-3"), eq(hospital.getId())` chain.

When writing the next test against a repository finder, default to
raw values; reach for `eq()` only when the call genuinely needs
mixed matchers.

### Mockito — never rely on `Optional`-default for unstubbed methods

**Caught:** PR A04 round 1 Copilot review (High). Test relied on
Mockito's default return for an unstubbed `Optional<T>`-returning
repository method. The Copilot comment claimed Mockito returns
`null` for `Optional` returns; that's incorrect for Mockito 2.x+
(returns `Optional.empty()` via `RETURNS_DEFAULTS`), and the test
in fact passed — but the lesson is sound.

**Pattern to follow:** explicit `when(repo.method(...))
.thenReturn(Optional.empty())` stub plus a
`verify(repo).method(...)` even when "the default works." Two
reasons:

1. Future-proofs against Mockito strictness changes
   (`STRICT_STUBS`, `Mockito.lenient()` shifts, a Spy-default
   switch).
2. Makes the test's expected behaviour readable — a future reader
   shouldn't have to know Mockito's default-answer ladder to
   understand why the test passes.

The cost is two lines per test. Cheap.

## Anti-patterns surfaced by Copilot + Sonar + CodeQL (2026-05-18 batch — multi-row foundation review)

The 9-row foundation-pass review surfaced a class of findings that
required structural fixes, not annotation tweaks. Each is captured
here so the next dynamic-SQL / coverage-gate / self-call situation
gets handled correctly the first time.

### Backend — Sonar S2077 hotspots & CodeQL "Query built from user-controlled sources"

**Caught:** PRs on rows 32 + 33 — `KpiMaterializedViewRefreshScheduler`
flagged for `REFRESH MATERIALIZED VIEW CONCURRENTLY <matview>` concat,
`TenantProvisioningService` flagged for `CREATE SCHEMA / GRANT USAGE /
ALTER DEFAULT PRIVILEGES` concat. **Five rounds of fixes** progressively
failed:

1. Plain regex allowlist → CodeQL still flags (regex is not a sanitiser).
2. Regex + double-quote wrap → CodeQL still flags (taint flows through
   the quote-wrap helper).
3. Regex + char-by-char rebuild into a fresh `StringBuilder` → CodeQL
   happy, **Sonar S2077 still fires** (it's a *hotspot* rule that flags
   any non-literal SQL string, regardless of sanitisation).
4. (Final) Pick the right shape for the call site:

**Pattern to follow:**

| Situation | Fix |
|---|---|
| Closed set of identifiers known at compile time (e.g. 3 fixed matview names) | Enum + per-value branch with **inlined literal SQL** in each `executeUpdate` call. No concat anywhere. Sonar + CodeQL both clean. |
| Runtime-supplied identifier (tenant schema, app role) with NO way to enumerate | `@SuppressWarnings("java:S2077")` scoped to a tightly-bounded private helper, with a Javadoc explaining the two-stage guard (allowlist regex + char-by-char rebuild) and why DDL can't be parameterised. |
| Runtime value that goes into a `WHERE` clause, not an identifier | Use JPA `setParameter("name", value)` — bind parameters are sanitised by the driver. Never reach for the concat path. |

The Sonar suppression form is `@SuppressWarnings("java:S2077")`
(modern SonarQube key — `squid:S2077` is the legacy form). Always pair
it with `@SuppressWarnings("java:Sxxxx")` on the *narrowest* method that
contains the offending call. Justify in Javadoc, not in a code comment
— Javadoc renders in the rendered class doc; comments don't.

CodeQL's "Query built from user-controlled sources" is *separate* from
Sonar S2077 and has a different fix pathway: it accepts a char-by-char
rebuild as a sanitiser because the loop's output is derived from
constant-valued branches in the allowlist. The two rules have to be
satisfied independently — `@SuppressWarnings("java:S2077")` does NOT
satisfy CodeQL, and a char-by-char rebuild does NOT satisfy S2077.

For DDL specifically: PostgreSQL DOES NOT accept bind parameters for
identifier positions (`CREATE SCHEMA $1` is a parser error). So
`setParameter` is not an option — the suppression is the genuine answer.

### Backend — `@Transactional` self-invocation never works

**Caught:** PRs on rows 20, 22, 32, 41 — Sonar S2229 "Call transactional
methods via an injected dependency instead of directly via 'this'."
Four classes had the same shape:

```java
@Transactional public X update(args)            { return update(args, null); }
@Transactional public X update(args, ifMatch)   { /* real work */ }
```

The second call goes through the proxy; the first call's internal
delegate does NOT — Spring's transactional proxy is invocation-time,
not bytecode-rewrite. The inner `@Transactional` annotation is a lie.

**Pattern to follow:** for any class with N public overloads that all
need the same transactional boundary, extract the work into a
**private** non-transactional `do<Operation>` helper and have each
public `@Transactional` overload call it directly:

```java
@Transactional public X update(args)          { return doUpdate(args, null); }
@Transactional public X update(args, ifMatch) { return doUpdate(args, ifMatch); }
private X doUpdate(args, ifMatch) { /* real work */ }
```

Both public methods now go through the proxy for external callers;
the private helper inherits the active transaction either way. No
self-call, no Sonar finding, no surprising runtime semantics.

The same fix applies when the orchestrator has `@Transactional` and
calls `@Transactional protected` workers inside the same bean (the
`KpiMaterializedViewRefreshScheduler` pattern). For matview-refresh-
class code the answer is different: `REFRESH MATERIALIZED VIEW
CONCURRENTLY` cannot run in a transaction at all, so the fix is to
drop `@Transactional` everywhere AND borrow a `Connection` directly
from `DataSource` with `setAutoCommit(true)`. The Spring `@Scheduled`
entry-point does not create an outer transaction by default.

### Backend — PostgreSQL `REFRESH MATERIALIZED VIEW CONCURRENTLY` is incompatible with transactions

**Caught:** PR on row 32 follow-on. The first foundation pass used
`@Transactional protected void refreshOne(...)`. CONCURRENTLY rejects
in-tx execution at runtime → every CONCURRENTLY attempt failed and
the code always took the slow non-concurrent fallback (which holds an
ACCESS EXCLUSIVE lock — defeating the whole purpose of the refresher).

**Pattern to follow:** when wrapping PostgreSQL operations that have
"cannot run in a transaction block" semantics (`REFRESH MATERIALIZED
VIEW CONCURRENTLY`, `CREATE INDEX CONCURRENTLY`, `REINDEX CONCURRENTLY`,
`VACUUM`, `CLUSTER ... CONCURRENTLY`), the implementation must:

1. NOT be `@Transactional` (and not be called from a `@Transactional`
   caller within the same bean).
2. Borrow a JDBC `Connection` from `DataSource` directly and force
   `setAutoCommit(true)` BEFORE the call.
3. Restore the prior autocommit state in `finally` so the pooled
   connection returns to its expected default.
4. Catch `SQLException` per-statement and decide whether to wrap as
   `UncategorizedSQLException` (carries the SQL + raw cause) or
   degrade gracefully.

Document the constraint in a class-level Javadoc paragraph so the next
contributor doesn't reflexively annotate the method.

### Backend — `Page` vs `List` for sectioned pagination

**Caught:** PR on row 22 follow-on (`PatientEverythingService`). The
vitals + lab sections fetched with `Pageable` but the repository
returned `List<T>` — `Page.hasNext()` is the canonical "more rows
beyond this page" signal, and a `List<T>` has no such concept, so the
`hasMore` flag could never flip true even when the page was full.
Result: the FHIR `Bundle.link[next]` continuation was missing
exactly when it was needed.

**Pattern to follow:** any time a section's `hasNext` / "more rows
exist" decision drives downstream emission (FHIR continuation, scroll
cursor, "load more" button), the repository method MUST return
`Page<T>`, not `List<T>` with a `Pageable`. The two return types
trigger different SQL — `Page` issues a `count(*)` query alongside
the SELECT so `hasNext()` is real.

If the existing list-returning method is used elsewhere and you don't
want to change its callers, add a new method with a `findPageBy...`
prefix:

```java
List<T>  findByPatient_IdAndHospital_Id(UUID p, UUID h, Pageable page);
Page<T>  findPageByPatient_IdAndHospital_Id(UUID p, UUID h, Pageable page);
```

Spring Data treats `Page` as a subject keyword and derives the same
query under it — the return type, not the method name, controls
whether the count query fires.

### Backend — "Brain Method" / cognitive-complexity gates need per-section helpers + a context object

**Caught:** PR on row 22 follow-on. `everythingForPatient` was an
80-line method with cognitive complexity 17 (> 15 gate) and 26
variables — Sonar flagged it as a "Brain Method" with three
remediation options: LOC, complexity, or nesting. The fix had to be
structural.

**Pattern to follow:** when a method's signature is

> orchestrator that loops/branches over N similar resource sections,
> each with its own filter / page / mapper

extract:

1. A private inner static `SectionContext` record/class carrying the
   per-request state (ids, page request, hasMore accumulator,
   since-filter check) — replaces the `boolean[]{false}` mutable
   holder pattern.
2. One `append<Section>` private method per section, each taking
   `(Bundle, SectionContext)` and contributing entries. The
   orchestrator becomes a 10-line list of method calls.
3. The since-filter / type-includes / cursor-page-size logic lives
   on the context object so each helper has a uniform call shape.

The same split keeps the `if-includes-X` ladders single-condition
(complexity +1 per section, not +5).

### Backend — N+1 alias / lookup inside a per-candidate scoring loop

**Caught:** PR on row 25 follow-on (`EmpiProbabilisticMatcher.score`).
The scorer called `empiService.findIdentityByAlias(NATIONAL_ID,
query.nationalId())` once per candidate, but the alias being looked
up is the same per-request value every time — N candidates × 1 alias
lookup = N identical DB calls.

**Pattern to follow:** any per-request scalar derived from the inbound
request that's consumed inside a per-candidate / per-row loop MUST be
resolved ONCE at the entry point and threaded as a parameter into the
loop body. Don't put the resolution inside the loop "for locality" —
the JIT can't hoist a DB call out, and the test that pins this
behaviour is a simple `verify(repo, times(1)).findX(...)`.

Add a regression test that asserts the lookup runs exactly once per
request, so the future "let me inline this back into the helper"
refactor catches itself.

### Frontend — RxJS `switchMap` returning a bare array doesn't emit

**Caught:** PR on row 24 follow-on. The hospital typeahead had:

```ts
switchMap((term) => {
  if (term.trim().length < 2) {
    this.hospitalSearchLoading.set(false);
    return [];  // ← bug
  }
  return this.hospitalService.searchHospitals(...);
})
```

RxJS treats a bare `[]` as an empty iterable that completes without a
`next` emission — the subscriber's `next` handler never runs, so
`hospitalOptions()` keeps its previous (stale) value. User deletes
back to 1 char → old suggestions stay visible.

**Pattern to follow:** every `switchMap` branch MUST return an
`Observable`, never a bare array or primitive. For the "below
threshold" / "early exit" / "no need to fetch" path, use `of([])` (or
`of(null)` / `of(...)` matching the source observable's element type)
AND explicitly reset any caller-visible state inside the branch:

```ts
switchMap((term) => {
  if (term.trim().length < 2) {
    this.hospitalSearchLoading.set(false);
    this.hospitalOptions.set([]);     // explicit reset
    return of<HospitalResponse[]>([]); // explicit emission
  }
  this.hospitalSearchLoading.set(true);
  return this.hospitalService.searchHospitals(term.trim(), 20);
})
```

The dual reset (state signal + observable emission) is intentional —
the signal reset covers the case where the subscriber's `next` is
async, and the observable emission covers downstream operators that
expect a continuous stream.

### Backend — Sonar coverage gate fires per-file, not per-module

**Caught:** Multi-row PR Quality Gate failed at 64.3% on new code —
two new classes (`DicomWebHttpClient` 0%, `KpiMaterializedViewRefreshScheduler`
0%) dragged the aggregate below 80% even though the module's overall
coverage was healthy.

**Pattern to follow:** every NEW non-DTO / non-config class needs a
unit test class shipped in the SAME PR. Don't rely on integration
tests to cover the new code — Sonar's coverage metric weighs each
file independently.

For HTTP-client classes (RestClient bridges to external services),
use Spring's `MockRestServiceServer.bindTo(builder)` to mock the
upstream. The production class needs a package-private constructor
that accepts a `RestClient` so the test can wire the mock-backed
one — keep the public constructor wiring the production timeouts:

```java
public DicomWebHttpClient(DicomProxyProperties properties) {
    this(properties, RestClient.builder().requestFactory(...).build());
}

DicomWebHttpClient(DicomProxyProperties properties, RestClient restClient) {
    this.properties = properties;
    this.restClient = restClient;
}
```

For scheduler / batch-job classes that wrap JDBC operations, mock
the `DataSource → Connection → Statement` chain with Mockito and
assert on the exact SQL `executeUpdate` was called with — that
both verifies the literal SQL contract AND drives the
per-matview-branch coverage to 100%.

For service classes with multiple branch / null-check ladders,
write tests that hit each branch explicitly (alias-only path,
name-only path, both, neither). The previous "happy path only" test
pattern leaves 50%+ of branches uncovered and the coverage gate
fails on the second PR.

### Mockito strict-stubbing — every `when(...)` must be exercised

**Caught:** Two `EmpiProbabilisticMatcherTest` test methods failed
with `UnnecessaryStubbingException` after the matcher refactor moved
the alias lookup out of the per-candidate loop. The tests stubbed
`empiService.findIdentityByAlias(...)` but the new code path
short-circuits before reaching that lookup when nationalId is null.

**Pattern to follow:** when refactoring production code in a way that
changes which collaborators are invoked, audit the existing test's
`when(...)` stubs at the same time. With `@ExtendWith(MockitoExtension.class)`
+ default strict mode, an unused stub fails the test even if the
assertions pass.

Two fixes available depending on intent:

- Remove the stub if the new code legitimately doesn't call that
  collaborator on that path (preferred — keeps tests honest).
- Wrap in `lenient().when(...)` if the stub is conditional but
  preserved for documentation value (use sparingly — it disables
  the strict-mode safety net).

### Backend — DDL `entityManager.createNativeQuery` and JaCoCo coverage

**Caught:** `TenantProvisioningServiceTest` covers 87% of
instructions but only 60% of branches because the `toSafeSqlIdentifier`
helper's "char outside the allowlist" branch is unreachable — the
regex guard at the entry-point rejects those inputs before they
reach the rebuild loop.

**Pattern to follow:** when a defence-in-depth guard's else-branch is
provably unreachable (the regex above the rebuild loop rejects the
same character class), accept the lower branch-coverage number. Don't
write a test that reflectively bypasses the regex to hit the inner
guard — the test would assert on a state the production code cannot
reach, and removing the inner guard later (because it's "dead")
would silently weaken the defence.

Sonar's coverage gate is on instructions, not branches, so the
unreachable inner branch doesn't fail the gate. Document the
unreachable branch in a Javadoc line ("Unreachable — SAFE_IDENTIFIER
already rejects this. Kept as a defence-in-depth shield.") so the
next reader doesn't reflexively delete it.

### Coverage gap on PatientEverythingService — happy-path test deferred but acceptable

**Caught:** `PatientEverythingService` post-refactor sits at 26%
instructions / 11% branches because the existing tenant-gate test
only covers the four early-exit security branches; the per-section
append helpers and `SectionContext` are exercised only by the
end-to-end `PatientEverythingEnabledIT` which isn't counted in unit
coverage.

**Pattern to follow:** for FHIR / interop classes with deep
collaborator graphs (mapper × repository × paging × audit), the
unit-test-side coverage is honest-to-low because mocking everything
hides bugs the IT catches. Document in the test class comment that
"happy-path is covered E2E by `<ITClassName>` — these tests pin only
the security branches" and accept the unit-coverage number.

If Sonar's per-file gate fails despite the IT, the answer is to add
a "minimal happy-path" unit test that stubs each per-resource page
query to return an empty `Page`, exercises every `append*Section`
method, and asserts a Bundle with just the Patient entry comes back.
That doesn't add bug-finding value, but it pushes coverage above the
80% line without faking it.

## Co-author tag

Every commit Claude authors carries:

```
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

This is the team's contribution-attribution convention. The model
version in the tag reflects the actual model used.

## Reference branches (recent exemplars)

- `feat/v1.1-oru-r01-lab-persistence` — full foundation-pass shape.
- `feat/v1.1-adt-admission-encounter-sync` — foundation-pass + Copilot
  fix commit + a CI-failure repair commit.
- `feat/v2.0-hipaa-posture` — docs-only foundation pass + Copilot fix.
- `feat/v1.1-fhir-write-api` — Patient PUT + conditional POST
  foundation; Encounter + Observation explicitly deferred in the
  cell text (row 20).
- `feat/v1.1-cds-hooks-public-discovery` — sandbox CORS allowlist +
  prefetch templates + five-case discovery IT (row 27).
- `feat/v2.0-synthetic-monitoring` — Blackbox-exporter + alert rules
  + runbook; multi-geo rollout explicitly deferred in the cell text
  (row 43).
- `feat/v1.1-kpi-dashboard-service` — KpiDashboardService + Controller
  + Angular sub-component shell; materialized-view conversion
  explicitly deferred (row 32).
- `chore/roadmap-sync-post-overnight` — batched roadmap sync after
  multiple merges.
- `chore/skills-update-post-4-picks` — repo-wide skills file refresh
  after four feature foundation passes (this branch's commit).
- `feat/v2.0-schema-per-tenant-scripts` — three operator scripts +
  cache-invalidate REST surface for the row-33 cutover, with the
  drain-before-copy + SAFE_REGEX-everywhere lessons codified in the
  follow-up commit (PR #356, 2026-05-17).
- `feat/v1.1-kpi-dashboard-follow-on` — P50 median + sparkline +
  axe-smoke for row 32, with the `T[]`-not-`Array<T>`, aria-label
  i18n, setParameter-constants, and split-aggregation-method
  lessons codified in the follow-up commit (PR #357, 2026-05-17).
- `feat/v1.1-adt-auto-create` — A01 Admission auto-create for
  row 24, with the cross-tenant-by-stamping-receiving-hospital,
  AcuityLevel-enum-values-in-runbook, and PENDING-vs-ACTIVE
  lessons codified in the follow-up commit (PR #358, 2026-05-17).
- `feat/v1.1-adt-auto-create-encounter` — A04 Encounter
  auto-create for row 24, with the SonarQube-duplication-on-new-code
  and Mockito-explicit-stub lessons codified in the round-1 follow-up
  commit (PR A04, 2026-05-17).
