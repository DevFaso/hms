# C2: idle-session refresh and portal sign-out coverage

Base: ec1682e152d507666148e58cd69933518b41a9fc. Updated 12 November 2026.

Two MVC cases compose the real AuthController, IdleSessionGate and InMemoryIdleSessionTracker with a complete mutable Clock. Repositories, token issuance and transports are stubs; security filters are excluded. Refresh before expiry rotates/renews the idle window. At the exact expiry boundary, retries return 401 with the actual idle challenge, record failure audits and do not claim a refresh token, issue either token or write a refresh cookie. The previous static message source is restored. The gate's second constructor argument is the exemption-role list; empty means this fixture's nurse is subject to the gate.

Five portal idle cases cover boundary, activity renewal, unlock, lock restoration on a genuinely new TestBed/service instance and removal of each captured listener. Production attaches all seven listeners to document; the registration assertions require mousedown/visibilitychange, so cleanup cannot pass on an empty capture. Events bubble.

Two interceptor cases cover the generic 401 flow when a refresh refusal carries the idle challenge, with and without that challenge on the original clinical response. They require one sign-out/navigation, no request replay and no installed token. A clinical idle challenge permits zero or one refresh attempt, allowing a future early-sign-out optimization; the generic clinical 401 requires one refresh. Current production has no idle-header-specific branch. These tests do not claim otherwise.

Verification in the isolated checkout:

- `./gradlew.bat :hospital-core:test --tests '*IdleSessionRefreshIntegrationTest' --tests '*IdleSessionGateTest' --tests '*InMemoryIdleSessionTrackerTest' -PtestForks=1 --console=plain`: exit 0, 19 passed (2 MVC, 10 gate, 7 tracker).
- `npm.cmd run test:headless -- --browsers=ChromeHeadlessNoSandbox --include=src/app/core/idle.service.spec.ts --include=src/app/interceptors/error.interceptor.spec.ts --include=src/app/auth/auth.service.spec.ts --karma-config=<local loopback ephemeral-port config>`: final exit 0, 104 passed, including 5 idle and 2 interceptor cases. The local config delegates to repository Karma and uses port 0; no CI configuration changed.
- Portal `npm.cmd run lint`, final `npm.cmd run format:check`, report Prettier and `git diff --check`: exit 0. Java compiled; no Java formatter is configured.

An initial expanded run failed one listener-count assumption (103 passed, one failed); the test now checks actual registered callbacks instead of the mistaken fixed count. An initial nested-test format check failed and was corrected with Prettier. Those failures are superseded by the final results above.

No production behavior, durations, gates, baselines, database, real provider or dev/prod changes. This is component coverage with sequential JUnit execution, not deployed authentication/filter-chain/Redis proof. New-head CI is separate; no merge/deployment.
