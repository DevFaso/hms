# C10 — Organisation isolation composition coverage

## Scope and status

Assignment: `docs/qa/test-split-codex-claude-2026-11-12.md`, C10. Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`. Executed on 11 November 2026 (client date); report follows the assignment's 12 November filename.

**Partial:** nine new tests compose the real scope resolver, role validator, tenant predicate builder and admission service with repository stubs. They do not prove persisted isolation across all repositories and services. The brief prohibits database access; clarification about a disposable test database remains unanswered, so no database was started or accessed.

## Changes and evidence

`OrganisationIsolationCompositionTest` creates fake `cdx-` organizations A and B, hospitals and active admissions in memory. It verifies both directions of foreign-organization read/cancel refusal, forged hospital-header refusal, own-hospital reads, hospital-only predicate construction, refusal for an unassigned sibling hospital in the same organization, and refusal when an organization assignment grants no hospital scope.

Negative cases assert no repository save, unchanged admission status and no mapper, bed, stay-encounter or invoice effects. Thread-local hospital and security contexts are cleared after every case. The own-hospital positive controls prevent a blanket-refusal implementation from satisfying the suite.

The related source guard tests also ran. Their existing exception lists were not changed. They are ratchets, not proof that every guarded service executes correctly.

No production code, database, shared records, live endpoint, credentials, Railway setting, CI gate, threshold or baseline changed.

## Verification

Commands ran in `C:/dev/hms-qa-c1`. Logs are retained locally under `C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/`.

| Command                                                                                                                                                                                                                                                                       | Exit | Result / log                                                                                       |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---- | -------------------------------------------------------------------------------------------------- |
| `./gradlew.bat :hospital-core:test --tests '*OrganisationIsolationCompositionTest' --tests '*ActingScopeResolverTest' --tests '*TenantScopeSpecificationTest' --tests '*ServiceWriteTenantLoadCoverageTest' --tests '*ActingScopeCoverageTest' -PtestForks=1 --console=plain` | 0    | 54 passed: 9 new, 32 resolver, 4 specification, 6 write-load guard, 3 scope guard; `c10-tests.log` |
| `./gradlew.bat :hospital-core:test --tests '*OrganisationIsolationCompositionTest' -PtestForks=1 --console=plain`                                                                                                                                                             | 0    | Final rerun after removing an unused import: 9 passed; `c10-final-tests.log`                       |
| `npm.cmd run lint` (in `hospital-portal`)                                                                                                                                                                                                                                     | 0    | `c10-lint.log`                                                                                     |
| `npm.cmd run format:check` (in `hospital-portal`)                                                                                                                                                                                                                             | 0    | `c10-format.log`                                                                                   |
| `git diff --check`                                                                                                                                                                                                                                                            | 0    | Whitespace check before commit                                                                     |

No Java formatter/linter is configured in this repository. Java compilation and JUnit execution passed; portal lint/format results do not imply Java formatting coverage.

## Remaining work

Criteria API objects and repositories are mocked: no SQL predicate execution, database row isolation, transaction rollback, concurrent mutation, Spring security-filter behavior, or durable side effects are proved. The requested repository/service integration sweep remains incomplete under the current no-database constraint. These tests do not close the live multi-organization checklist item or authorize a deployment.
