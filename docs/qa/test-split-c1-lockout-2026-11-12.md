# C1: account-lockout repository coverage

Source assignment: `test-split-codex-claude-2026-11-12.md`, C1. Execution started 2026-11-11 (America/New_York); the date in this filename follows the assignment label. Base: `ec1682e15` (`origin/develop`).

The existing controller tests replace LoginAttemptService with a mock, so they do not prove that consecutive HTTP login attempts accumulate a real lock. LoginLockoutIntegrationTest wires Spring MVC, the real AuthController, LoginAttemptService and InMemoryLoginAttemptStore together. All repositories, authentication, token generation, audit storage and notification collaborators are stubs. No database, live application, provider, credentials or listening HTTP service is used. Security filters are excluded as in AuthControllerTest; this is a focused MVC/component integration test, not a full security-filter-chain or deployed-E2E test.

## Cases

- Five invalid password attempts return 401; the sixth is refused with 423 before authentication. The remaining real lock is bounded by the existing 15-minute policy.
- FR, EN and ES lock responses resolve from the application bundles. Five LOGIN_FAILURE events and the ACCOUNT_LOCKED event are verified; failed requests do not mint an access token.
- An unknown username receives the same threshold and locked response as a known one.
- A successful login below the threshold clears the real counter; another complete failure budget is allowed before locking again.
- An expired store deadline is purged, allowing a fresh failure budget. The test seeds an already-past deadline through the store API; it does not wait 15 minutes or change application settings. Reset by account ID unlocks the real store.

## Scope and limitations

Production sources, policy thresholds, CI configuration and baselines are unchanged. Redis/multiple-instance behavior is outside C1's new test; the existing Redis store tests are separate. This does not tick the living checklist's live-E2E requirement. No merge or ready action is authorized.

## Verification

### Review follow-up — 12 November 2026

Addressed conversation review 6822977121: expiry fixtures obtain their key from an actual service/store call, first prove a positive lock and then prove expired-state removal by a fresh failure count of one. FR/EN/ES expectations are independent literals so bundle fallback cannot satisfy them. The previous static message source is restored, all observed store keys are cleared between cases, and both 401/423 parity plus unknown-user audit behavior are checked. Removed the unused import and the inaccessible-artifact claim. The service currently keys known accounts by per-test UUID, so username/IP contamination was not a confirmed current defect; cleanup is nevertheless explicit.

Reverification: the same selected Gradle command below passed 90 tests (exit 0). Portal `npm.cmd run lint`, `npm.cmd run format:check`, report Prettier and `git diff --check` each exited 0. No production change. Detailed command outcomes are in this committed report; local evidence logs are `c1-review-tests.log`, `c1-review-lint.log` and `c1-review-format.log`.

The first expanded run exited 1 (89 passed, one failed): the new audit expectation incorrectly treated every null-user audit as an unknown-user request, but known-account lock refusals also record no user ID. Assertions now identify the actual unknown-request calls and validate their event/user fields. Final rerun evidence is `c1-review-final-tests.log`.

Original command results are recorded in the PR description. Initial focused run: six new integration cases plus twelve existing LoginAttemptServiceTest cases passed. Final run: 90 tests passed (AuthControllerTest: 72, LoginLockoutIntegrationTest: 6, LoginAttemptServiceTest: 12), zero failures/errors. Command: `./gradlew.bat :hospital-core:test --tests '*LoginLockoutIntegrationTest' --tests '*LoginAttemptServiceTest' --tests '*AuthControllerTest' -PtestForks=1 --console=plain`, exit 0. `npm ci --ignore-scripts --no-audit --no-fund` (locked dependencies), `npm run lint`, `npm run format:check` and `git diff --check` each exited 0. Frontend lint and format checks passed; those existing checks do not format or lint Java. The repository does not configure a separate Java formatter/linter task; Java compilation and git diff --check provide the applicable local checks.
