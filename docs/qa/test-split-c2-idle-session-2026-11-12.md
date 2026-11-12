# C2: idle-session repository coverage

Assignment: `test-split-codex-claude-2026-11-12.md`, C2. Executed 2026-11-11 (America/New_York); filename follows the assignment date. Base: `ec1682e15` (`origin/develop`).

Two Spring MVC integration cases connect the real AuthController, IdleSessionGate and InMemoryIdleSessionTracker with a deterministic clock. Repositories, token issuance and transports are stubs; security filters are excluded like the existing controller slice. Refresh just before expiry rotates and renews the window. At the exact expiry boundary, refresh returns 401 plus the idle challenge; repeated attempts do not revive the session, claim the old refresh token or mint access tokens, and both refusals are audited.

Five Karma cases cover the portal idle boundary, activity renewal, persisted lock, unlock/reset, and stopped timer/listener cleanup. A sixth case drives the real error interceptor through an idle-timeout refresh refusal: it signs out once, redirects to login and never replays the clinical request or installs a token. Existing AuthService and interceptor cases run alongside it.

## Local verification

### Review follow-up — 12 November 2026

Addressed conversation review 6822980109. The backend fixture restores the previous static message source, uses a complete mutable Clock (millis/instant/zone/withZone), and checks that idle refusals produce neither access/refresh tokens nor a refresh cookie. The two interceptor cases mirror the actual idle challenge on the refresh refusal and cover clinical 401s with and without that challenge; they assert one refresh attempt, one sign-out/navigation and no clinical replay/token install. The production interceptor intentionally uses its generic 401 path: it does not special-case the challenge or skip renewal. No new idle-specific product behavior is claimed or introduced.

The persisted-lock test now creates a genuinely new service after resetting TestBed. Cleanup records every registered event/listener and verifies each is removed; activity events bubble. Initial portal run: exit 1, 103 passed / one failed because the new test assumed six listeners instead of the actual seven. It now checks every captured listener rather than a mistaken count. Final same portal command below: 104 passed, exit 0. Backend command below: 19 passed, exit 0. Lint, final format check, report Prettier and whitespace checks exited 0. The initial format check failed on the nested parameterized test and was corrected with Prettier. No production changes. Logs: `c2-review-tests.log`, `c2-review-final-portal-tests.log`, `c2-review-lint.log`, `c2-review-final-format.log`.

- `./gradlew.bat :hospital-core:test --tests '*IdleSessionRefreshIntegrationTest' --tests '*IdleSessionGateTest' --tests '*InMemoryIdleSessionTrackerTest' -PtestForks=1 --console=plain`: exit 0, 19 passed (2 new +17 existing), zero failures/errors. Initial compile caught a test fixture List/Set mismatch; corrected to the repository's Set contract before this successful run.
- `npm run lint`: exit 0.
- `npm run test:headless -- --browsers=ChromeHeadlessNoSandbox --include=src/app/core/idle.service.spec.ts --include=src/app/interceptors/error.interceptor.spec.ts --include=src/app/auth/auth.service.spec.ts --karma-config=<local ephemeral-port config>`: exit 0, 103 passed. Karma binds loopback port 0; the actual port is assigned by the OS, not shared 9876. The local config delegates to the repository launcher and is not a CI change.
- Prettier write on the two touched specs and `npm run format:check`: exit 0. Existing frontend checks do not cover Java; Java compiled and `git diff --check` passed.

Production sources, idle durations, CI gates and baselines are unchanged. No database, live provider, dev/prod URL or credentials were used. HTTP in the Java test is MockMvc in-process; portal API traffic is HttpTestingController stubs. This is not deployed E2E or full token/security-filter verification, and does not tick the live checklist. Full suites and CI/Sonar are separate from these local touched tests. Keep the PR draft; never ready, merge or deploy automatically.
