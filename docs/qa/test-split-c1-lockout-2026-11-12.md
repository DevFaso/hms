# C1: account-lockout MVC coverage

Base: ec1682e152d507666148e58cd69933518b41a9fc. Updated 12 November 2026.

The MVC slice imports the production LoginAttemptService, InMemoryLoginAttemptStore and LocaleConfig. Repositories, authentication, token issuance and audit persistence are stubs; security filters are disabled. A Mockito spy observes the real store without replacing its behavior. The configured production MessageSource bean serves independent literal FR/EN/ES expectations.

Six cases cover threshold/423/audit in three languages, known/unknown 401 and 423 parity, successful-login reset and expiry. The expiry fixture derives its key from an actual service/store call and first proves a positive lock. It checks direct purge behavior, then independently re-seeds an expired row and goes straight to HTTP with no intervening read or clear. Keeping the expired failure count makes HTTP lock early and fails the test. All observed store keys are cleared after each case. The prior static message source and request locale/security state are restored; this fixture assumes the repository's sequential JUnit execution.

Verification in the isolated checkout:

- `./gradlew.bat :hospital-core:test --tests '*LoginLockoutIntegrationTest' --tests '*LoginAttemptServiceTest' --tests '*AuthControllerTest' -PtestForks=1 --console=plain`: 90 passed, exit 0, before the final production-bean/direct-HTTP fixture refinement. Its initial expanded audit assertion had exited 1 (89 passed, one failed); it wrongly treated known lock-refusal audits with null user IDs as unknown-user requests. The corrected assertions identify the actual unknown requests.
- Final fixture verification: `./gradlew.bat :hospital-core:test --tests '*LoginLockoutIntegrationTest' -PtestForks=1 --console=plain`: six passed, exit 0.
- Portal `npm.cmd run lint` and `npm.cmd run format:check`: exit 0 on unchanged portal sources. Report Prettier and `git diff --check`: exit 0. Java compiled; no separate Java formatter is configured.

No production sources, thresholds, gates or baselines changed. No database, live account, provider or dev/prod request. This does not prove Redis sharing, deployed filter behavior, timing-attack parity or full authentication security. New-head CI is separate evidence; no merge or deployment.
