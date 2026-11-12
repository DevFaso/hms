# C3: MFA enrollment, role scope, replay and expiry coverage

Base: ec1682e152d507666148e58cd69933518b41a9fc. Updated 12 November 2026.

Four MVC cases compose the real MfaController/MfaService, secret/QR generation, TOTP generation/verification and local failure budget. Repositories and token issuance are stubs. Stored conditional-step claims are simulated, not SQL concurrency proof. User IDs and usernames are unique per case.

- Enrollment confirms a previous-step code; login uses the current step. The picked super-admin role stays exclusive despite an extra loaded authority, in both response and captured access-token descriptor. Reused login TOTP is refused for the invalid-code reason without burning a new challenge. Replaying the spent challenge with a fresh valid later-step code is refused for the invalid-token reason. Access and refresh issuance each occur once.
- Wrong/expired TOTP candidates are checked against the real service before use, then refused for the invalid-code reason. The challenge remains unspent; no access/refresh token or cookie is issued.
- An invalid-challenge stub is refused for the invalid-token reason without issuance or consuming state.
- The expiry case uses an in-process signing key and the real JwtTokenProvider parser. The expired token copies all claims from a fresh generated token and changes only expiration. Both tokens delegate every challenge-parsing method used by the controller to the real provider. The expired token gets the invalid-token refusal without spend/issuance; the fresh token then completes the controller path and requests one full token pair. Full-session JWT issuance remains mocked.

Negative cases snapshot the TOTP step after enrollment and assert it remains unchanged, rather than requiring null or prescribing whether enrollment should consume a code. Enrollment security policy is unchanged. TOTP time advances through a test provider; JWT expiry uses a signed token five minutes in the past, not sleeps or a stubbed false result.

Verification in the isolated checkout:

- `./gradlew.bat :hospital-core:test --tests '*MfaEnrollmentLoginIntegrationTest' --tests '*MfaControllerTest' --tests '*MfaServiceTest' -PtestForks=1 --console=plain`: exit 0, 90 passed (4 integration, 34 controller, 52 service), before the final claim-matched controller-pair/refusal refinements.
- Final touched-test command: `./gradlew.bat :hospital-core:test --tests '*MfaEnrollmentLoginIntegrationTest' -PtestForks=1 --console=plain`: exit 0, four passed.
- Portal lint/format on unchanged portal sources, report Prettier and whitespace: exit 0. Java compiled; no Java formatter is configured.

No production code, policy, gates, baselines, database, real provider or live credentials changed. This does not prove complete JWT filter-chain authentication, SMART grants, SQL atomicity or deployed MFA security. New-head CI is separate evidence; no merge/deployment.
