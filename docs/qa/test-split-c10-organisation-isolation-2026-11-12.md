# C10 — Organisation isolation composition coverage

## Scope and status

Assignment: `docs/qa/test-split-codex-claude-2026-11-12.md`, C10. Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`. Executed on 11 November 2026 (client date); report follows the assignment's 12 November filename.

The initial nine composition tests use repository stubs. On 12 November 2026 the user approved disposable in-memory test databases, so the follow-up below adds actual H2 SQL queries and persisted admission service operations. This is targeted repository/service coverage, not proof of isolation across every repository method, clinical service or PostgreSQL deployment.

## Changes and evidence

`OrganisationIsolationCompositionTest` creates fake `cdx-` organizations A and B, hospitals and active admissions in memory. It verifies both directions of foreign-organization read/cancel refusal, forged hospital-header refusal, own-hospital reads, hospital-only predicate construction, refusal for an unassigned sibling hospital in the same organization, and refusal when an organization assignment grants no hospital scope.

Negative cases assert no repository save, unchanged admission status and no mapper, bed, stay-encounter or invoice effects. Thread-local hospital and security contexts are cleared after every case. The own-hospital positive controls prevent a blanket-refusal implementation from satisfying the suite.

The related source guard tests also ran. Their existing exception lists were not changed. They are ratchets, not proof that every guarded service executes correctly.

No production code, shared records, live endpoint, credentials, Railway setting, CI gate, threshold or baseline changed. The initial run used no database; the approved follow-up uses disposable H2 only.

## Verification

Original author commands ran in an isolated checkout. The named logs are local evidence, not committed artifacts; this PR's CI provides shared verification.

| Command                                                                                                                                                                                                                                                                       | Exit | Result / log                                                                                       |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---- | -------------------------------------------------------------------------------------------------- |
| `./gradlew.bat :hospital-core:test --tests '*OrganisationIsolationCompositionTest' --tests '*ActingScopeResolverTest' --tests '*TenantScopeSpecificationTest' --tests '*ServiceWriteTenantLoadCoverageTest' --tests '*ActingScopeCoverageTest' -PtestForks=1 --console=plain` | 0    | 54 passed: 9 new, 32 resolver, 4 specification, 6 write-load guard, 3 scope guard; `c10-tests.log` |
| `./gradlew.bat :hospital-core:test --tests '*OrganisationIsolationCompositionTest' -PtestForks=1 --console=plain`                                                                                                                                                             | 0    | Final rerun after removing an unused import: 9 passed; `c10-final-tests.log`                       |
| `npm.cmd run lint` (in `hospital-portal`)                                                                                                                                                                                                                                     | 0    | `c10-lint.log`                                                                                     |
| `npm.cmd run format:check` (in `hospital-portal`)                                                                                                                                                                                                                             | 0    | `c10-format.log`                                                                                   |
| `git diff --check`                                                                                                                                                                                                                                                            | 0    | Whitespace check before commit                                                                     |

No Java formatter/linter is configured in this repository. Java compilation and JUnit execution passed; portal lint/format results do not imply Java formatting coverage.

## Remaining work

The initial composition suite mocks Criteria API objects and repositories. The follow-up adds real SQL and flushed/reloaded rows for the paths described below; it does not prove PostgreSQL behavior, concurrent mutation, cross-request transaction boundaries, Spring security-filter behavior or every repository/service. Tests roll back their fake records after each case. These tests do not close the live multi-organization checklist item or authorize a deployment.

## Approved in-memory follow-up — 12 November 2026

`OrganisationIsolationJpaTest` uses a real Spring Data JPA slice, the production tenant repository configuration, assignment repository/accessor, scope resolver, role validator and admission repository. The admission service is instantiated directly: this proves service logic inside the test transaction, not its Spring proxy, method security or cross-request rollback behavior. Its datasource URL is asserted to begin with `jdbc:h2:mem:`. The slice creates a disposable embedded database and transactionally rolls back each case.

Fixtures contain two organizations, three hospitals (including a sibling of A that the A caller is not assigned to), and separate fake `cdx-` users, role assignments, patients, registrations and active admissions. All three hospital callers exercise four paths, for 12 cases:

- Patient find-by-ID, existence, unpaged/paged listing and extended search expose only the assigned hospital; explicitly naming a foreign hospital in search returns no rows.
- The real admission service reads an own-hospital positive control and refuses reads/cancellations at both foreign hospitals. Flushing and clearing the persistence context confirms all three stored statuses remain active.
- Successful cancellation changes only the own-hospital row, with its reason and timestamp; flushed/reloaded foreign rows remain active without cancellation metadata. Bed, encounter, billing and registration side effects are stubs, so their persistence is not claimed.
- Revoking an actual stored assignment produces a refused scope on the next resolver call and blocks a write, leaving the stored admission active.

The first broader run exited 1: 60 cases executed, 51 passed and nine new admission cases failed because H2 double-encoded the model's unused default JSON arrays (`"[]"` could not be read as a JSON object). The fake admissions now use null for the four optional JSON fields. No production mapping or clinical behavior changed. All three new patient SQL cases and the 39 existing related cases passed in that run. Counts are retained in `c10-first-inmemory-counts.json` and the log in `c10-inmemory-tests.log`.

Broader command:

```powershell
./gradlew.bat :hospital-core:test --tests '*OrganisationIsolationJpaTest' --tests '*OrganisationIsolationCompositionTest' --tests '*PatientRepositoryTenantScopeTest' --tests '*TenantIsolationTest' --tests '*OrganizationReadScopeIT' --tests '*ServiceWriteTenantLoadCoverageTest' --tests '*ActingScopeCoverageTest' -PtestForks=1 --console=plain
# Initial exit 1 as detailed above; this is not a green whole-suite result.
```

The existing source guards remain unchanged. Their exception lists, mock service tests and organization-controller tests do not imply that every repository finder was executed. H2 schema foreign-key/teardown warnings also mean this is not a PostgreSQL migration/constraint test.

Final rerun after the fixture correction:

```powershell
./gradlew.bat :hospital-core:test --tests '*OrganisationIsolationJpaTest' --tests '*OrganisationIsolationCompositionTest' -PtestForks=1 --console=plain
# Exit 0: 21 passed (12 H2 integration cases and 9 composition cases).
```

Final log: `c10-inmemory-final-tests.log`. Portal `npm.cmd run lint` and `npm.cmd run format:check` exited 0 (`c10-inmemory-lint.log`, `c10-inmemory-format.log`). Report Prettier check and `git diff --check` exited 0. No Java formatter/linter is configured; Java compilation and the final selected JUnit run passed.

Review follow-up: the composition negatives now verify that record-access, break-glass, sensitivity and reach collaborators are untouched and that the repository receives only the two expected reads, excluding every alternative write method. Own-patient existence has an explicit positive control. `ResourceNotFoundException` extends `RuntimeException` directly, not `BusinessException`, so the refused-scope assertion cannot pass on ordinary not-found. The Criteria mock is a targeted branch test, supplemented by the H2 paged/search tests, not interchangeable isolation coverage. The H2 JSON-array fixture workaround remains a limitation requiring a mapping/domain follow-up; it is not PostgreSQL mapping evidence.

Fresh broader verification after these corrections: `./gradlew.bat --no-daemon --offline :hospital-core:test -PtestForks=1 --tests "*OrganisationIsolationJpaTest" --tests "*OrganisationIsolationCompositionTest" --tests "*PatientRepositoryTenantScopeTest" --tests "*TenantIsolationTest" --tests "*OrganizationReadScopeIT" --tests "*ServiceWriteTenantLoadCoverageTest" --tests "*ActingScopeCoverageTest"` exited 0: all 60 selected cases plus four prerequisite observability checks passed, zero failures/errors. Only disposable H2 was used. This supersedes the earlier unverified broader run, without claiming production or complete isolation verification.
