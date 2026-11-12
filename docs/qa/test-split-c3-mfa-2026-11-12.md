# C3: MFA enrollment and second-factor login coverage

Assignment: `test-split-codex-claude-2026-11-12.md`, C3. Executed 2026-11-11 (America/New_York); filename follows the assignment date. Base: `ec1682e15`.

Four Spring MVC cases wire the real MfaController and MfaService, real enrollment-secret/QR generation, real SHA-1 TOTP code generation/verification and a real in-memory failure budget. A test TimeProvider makes code age deterministic. All user/enrollment/backup repositories and JWT issuance are stubs; the additional expiry case delegates challenge validation to a real JWT parser with an in-process signing key. Repository stubs retain enrollment state and simulate conditional step claims; they do not prove SQL atomicity or transactions.

1. Enroll, confirm, then complete the super-admin second-factor login: the picked role stays ROLE_SUPER_ADMIN despite an additional loaded authority, in both the response and the access-token descriptor. The same login TOTP cannot sign in again, even with a new challenge; that failed login does not burn the new challenge. Replaying the spent challenge with a fresh valid later-step code also fails. Exactly one full access token is requested. Confirmation uses the previous step and login uses the current step, so the test does not require accepting the same enrollment/login code. The production enrollment consumption policy is unchanged.
2. Wrong and expired TOTP codes never spend a challenge, record a used TOTP step or request a full token. Generated-code collisions with an accepted time window are explicitly excluded in the fixture.
3. A challenge declared invalid by JwtTokenProvider.isMfaToken is rejected before a current TOTP or challenge can be spent. Challenge validity and JWT encoding are mocked in this case; it proves invalid-token refusal. Expiry has its own real-parser case below.
4. A correctly signed challenge with a past expiration is checked by the real JwtTokenProvider parser and refused through the controller without spending a challenge/TOTP or requesting a full token. A fresh signed challenge with the same local key is the positive parser control. This adds cryptographic expiration evidence without a provider call or production key.

## Review follow-up — 12 November 2026

Addressed conversation review 6822983773: role widening is now observable, challenge replay is independent of TOTP replay, the former expiry stub is accurately named invalid-token coverage, and a fourth real-parser expiry case is added. Usernames are unique per test as well as user IDs. MfaService currently keys its failure budget by UUID, so cross-test username accumulation was not a confirmed current defect. No enrollment security policy was changed.

`./gradlew.bat :hospital-core:test --tests '*MfaEnrollmentLoginIntegrationTest' --tests '*MfaControllerTest' --tests '*MfaServiceTest' -PtestForks=1 --console=plain`: exit 0, 90 passed (4 integration, 34 controller, 52 service). Portal lint/format, report Prettier and whitespace checks exit 0. No production changes. Logs: `c3-review-tests.log`, `c3-review-lint.log`, `c3-review-format.log`.

## Local verification

- `./gradlew.bat :hospital-core:test --tests '*MfaEnrollmentLoginIntegrationTest' --tests '*MfaServiceTest' --tests '*MfaControllerTest' -PtestForks=1 --console=plain`: exit 0, 89 passed (3 new, 52 service, 34 controller), zero failures.
- After test-only import cleanup and making the generated-code collision set duplicate-safe, `./gradlew.bat :hospital-core:test --tests '*MfaEnrollmentLoginIntegrationTest' -PtestForks=1 --console=plain`: exit 0, all 3 new cases passed again.
- `npm run lint`, `npm run format:check`, `git diff --check`: exit 0. Existing portal lint/format checks do not cover Java, and no separate Java formatter/linter is configured; the Java tests compiled successfully.

No production source, database, credentials, provider calls, dev/prod URL, Railway setting, CI gate, threshold or baseline was changed or used. Spring MVC filters are excluded as in MfaControllerTest. The password/challenge-issuance boundary, full security filter chain, real persistence and concurrent SQL claims are outside these new cases. This is repository component integration coverage, not live E2E; do not tick the deployed QA checklist. Keep the PR draft; never ready, merge or deploy automatically.
