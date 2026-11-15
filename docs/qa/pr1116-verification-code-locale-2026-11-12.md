# PR #1116: locale-independent verification codes

On an Arabic formatting locale, `String.format("%06d", ...)` generated non-ASCII digits. The verification path accepts six ASCII digits, so a code sent under that locale could not be verified. Formatting with `Locale.ROOT` makes generated codes match the existing verifier without changing language selection, security limits or providers.

The regression test sets only the FORMAT locale to `ar-EG`, requests a code through the real service with a mocked SMS gateway, and verifies that the captured code completes the challenge. A finally block restores the previous FORMAT locale.

Verification in an isolated checkout:

- Before the fix: `./gradlew.bat :hospital-core:test --tests '*PhoneVerificationServiceImplTest.sentCodeVerifiesWithNonLatinDefaultLocale' -PtestForks=1 --console=plain` exited 1; the new case failed because the generated SMS contained non-ASCII digits.
- After the fix: `./gradlew.bat :hospital-core:test --tests '*PhoneVerificationServiceImplTest' --tests '*OrangeSmsGatewayImplTest' --tests '*SmsProviderSelectionGuardTest' --tests '*SmsCountryPolicyTest' --tests '*SmsTextLocalizationGuardTest' -PtestForks=1 --console=plain` exited 0: 48 passed (21 phone verification, 19 gateway, 5 provider guard, 2 country policy, 1 SMS text guard).
- Portal `npm.cmd run lint` and `npm.cmd run format:check`, report Prettier check and `git diff --check`: exit 0. Java compilation passed; no Java formatter is configured.

Gateway tests bind an OS-assigned loopback port; the service regression uses a mock. No real SMS, credentials, provider, shared database, dev/prod, Railway, gate or baseline changes. Local passing tests do not establish live Orange delivery or complete security review. No merge or deployment.
