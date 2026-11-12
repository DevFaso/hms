# C2: idle-session repository coverage

Assignment: `test-split-codex-claude-2026-11-12.md`, C2. Executed 2026-11-11 (America/New_York); filename follows the assignment date. Base: `ec1682e15` (`origin/develop`).

Two Spring MVC integration cases connect the real AuthController, IdleSessionGate and InMemoryIdleSessionTracker with a deterministic clock. Repositories, token issuance and transports are stubs; security filters are excluded like the existing controller slice. Refresh just before expiry rotates and renews the window. At the exact expiry boundary, refresh returns 401 plus the idle challenge; repeated attempts do not revive the session, claim the old refresh token or mint access tokens, and both refusals are audited.

Five Karma cases cover the portal idle boundary, activity renewal, persisted lock, unlock/reset, and stopped timer/listener cleanup. A sixth case drives the real error interceptor through an idle-timeout refresh refusal: it signs out once, redirects to login and never replays the clinical request or installs a token. Existing AuthService and interceptor cases run alongside it.

## Local verification

- `./gradlew.bat :hospital-core:test --tests '*IdleSessionRefreshIntegrationTest' --tests '*IdleSessionGateTest' --tests '*InMemoryIdleSessionTrackerTest' -PtestForks=1 --console=plain`: exit 0, 19 passed (2 new +17 existing), zero failures/errors. Initial compile caught a test fixture List/Set mismatch; corrected to the repository's Set contract before this successful run.
- `npm run lint`: exit 0.
- `npm run test:headless -- --browsers=ChromeHeadlessNoSandbox --include=src/app/core/idle.service.spec.ts --include=src/app/interceptors/error.interceptor.spec.ts --include=src/app/auth/auth.service.spec.ts --karma-config=<local ephemeral-port config>`: exit 0, 103 passed. Karma binds loopback port 0; the actual port is assigned by the OS, not shared 9876. The local config delegates to the repository launcher and is not a CI change.
- Prettier write on the two touched specs and `npm run format:check`: exit 0. Existing frontend checks do not cover Java; Java compiled and `git diff --check` passed.

Production sources, idle durations, CI gates and baselines are unchanged. No database, live provider, dev/prod URL or credentials were used. HTTP in the Java test is MockMvc in-process; portal API traffic is HttpTestingController stubs. This is not deployed E2E or full token/security-filter verification, and does not tick the live checklist. Full suites and CI/Sonar are separate from these local touched tests. Keep the PR draft; never ready, merge or deploy automatically.
