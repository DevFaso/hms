# C3: MFA enrollment and second-factor login coverage

Assignment: `test-split-codex-claude-2026-11-12.md`, C3. Executed 2026-11-11 (America/New_York); filename follows the assignment date. Base: `ec1682e15`.

Three Spring MVC cases wire the real MfaController and MfaService, real enrollment-secret/QR generation, real SHA-1 TOTP code generation/verification and a real in-memory failure budget. A fixed test TimeProvider makes code age deterministic. All user/enrollment/backup repositories and JWT issuance are stubs. Repository stubs retain enrollment state and simulate conditional step claims; they do not prove SQL atomicity or transactions.

1. Enroll, confirm, then complete the super-admin second-factor login: the picked role stays ROLE_SUPER_ADMIN. The same TOTP cannot sign in again, even with a new challenge, and replaying the used challenge also fails. Exactly one full access token is requested.
2. Wrong and expired TOTP codes never spend a challenge, record a used TOTP step or request a full token. Generated-code collisions with an accepted time window are explicitly excluded in the fixture.
3. An expired challenge is rejected before a current TOTP can be spent. Challenge validity and JWT encoding are mocked; this is the controller's expiry contract, not cryptographic JWT validation.

## Local verification

- `./gradlew.bat :hospital-core:test --tests '*MfaEnrollmentLoginIntegrationTest' --tests '*MfaServiceTest' --tests '*MfaControllerTest' -PtestForks=1 --console=plain`: exit 0, 89 passed (3 new, 52 service, 34 controller), zero failures.
- After test-only import cleanup and making the generated-code collision set duplicate-safe, `./gradlew.bat :hospital-core:test --tests '*MfaEnrollmentLoginIntegrationTest' -PtestForks=1 --console=plain`: exit 0, all 3 new cases passed again.
- `npm run lint`, `npm run format:check`, `git diff --check`: exit 0. Existing portal lint/format checks do not cover Java, and no separate Java formatter/linter is configured; the Java tests compiled successfully.

No production source, database, credentials, provider calls, dev/prod URL, Railway setting, CI gate, threshold or baseline was changed or used. Spring MVC filters are excluded as in MfaControllerTest. The password/challenge-issuance boundary, full security filter chain, real persistence and concurrent SQL claims are outside these new cases. This is repository component integration coverage, not live E2E; do not tick the deployed QA checklist. Keep the PR draft; never ready, merge or deploy automatically.
