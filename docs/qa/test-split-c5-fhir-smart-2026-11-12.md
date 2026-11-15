# C5: FHIR servlet and SMART discovery contracts — partial

Brief: `test-split-codex-claude-2026-11-12.md`, C5. Executed 11 November 2026 (client date). Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`.

## Evidence

`FhirServletStubContractIntegrationTest` initializes the actual `FhirConfig` servlet registration, all 11 real clinical resource providers, the real SMART builder/interceptor, capability provider and tenant interceptor. Provider repositories/services and the visibility/assignment resolver are mocks; no database context or socket starts. A fixed clock and empty mocked pages/collections support deterministic empty searches.

The FHIR URLs belong to a HAPI servlet, not Spring MVC. Requests therefore use Spring servlet request/response mocks through the actual `Servlet.service()` boundary rather than inventing an MVC controller around those URLs.

The 46 cases prove:

- R4 metadata advertises every registered clinical type with read/search interactions and the configured SMART authorize/token endpoints. HAPI's additional built-in `OperationDefinition` metadata is explicitly accounted for.
- SMART discovery returns configured authorize/token URLs, advertised `patient/*.read` and `user/*.read` scopes, and PKCE S256 without a hospital or provider call.
- Read and search of each of Patient, Encounter, Observation, Condition, MedicationRequest, Immunization, ServiceRequest, DiagnosticReport, Appointment, Slot and DocumentReference return 403 before provider collaborators when no hospital is bound or when the mocked role resolver denies access.
- An invisible named resource returns a FHIR OperationOutcome with 404 before provider read; the test verifies the exact visibility-resolver invocation, so an unrelated routing 404 cannot satisfy it.
- A permitted search through each actual provider serializes an empty FHIR Bundle without application errors.

Initial fixture runs failed: the mock servlet context omitted `/api`, causing incorrect routing; metadata included HAPI's framework-owned OperationDefinition; and a mocked clock returned no instant for Slot search. These test fixtures/expectations were corrected. No production change was needed.

## Verification

From isolated `C:/dev/hms-qa-c1`:

```powershell
./gradlew.bat :hospital-core:test --tests '*FhirServletStubContractIntegrationTest' --tests '*FhirTenantBoundaryInterceptorTest' --tests '*SecurityConfigFhirMatcherTest' --tests '*ObservationFhirResourceProviderScopingTest' --tests '*ServiceRequestFhirResourceProviderScopingTest' --tests '*DiagnosticReportFhirResourceProviderScopingTest' --tests '*AppointmentSlotFhirScopingTest' -PtestForks=1 --console=plain
# Final exit 0: 88 tests passed (46 new, 42 related).
cd hospital-portal
npm.cmd run lint
# Exit 0.
npm.cmd run format:check
# Exit 0.
cd ..
git diff --check
# Exit 0.
```

Portal lint/format do not cover Java. No Java formatter/linter is configured; the touched Java source compiled and ran. Logs: `C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/c5-verified-tests.log`, `c5-lint.log`, `c5-format.log`; failed fixture attempts are retained separately.

## What remains open

The original servlet/component suite uses mocked tenancy decisions and empty data. The approved in-memory follow-up below adds actual database, servlet-container and security-chain evidence. Neither suite proves positive populated read/search mapping for every resource, persisted cross-organisation FHIR rows, patient-context launch restriction, granted SMART scope enforcement, OAuth code/token exchange or a browser SMART launch. Advertised scopes are not proof that a grant is enforced.

Existing full-context FHIR/SMART ITs import `TestPostgresConfig`. They were initially not run under the no-database instruction. On 12 November 2026 the user explicitly allowed disposable in-memory test databases; this supersedes the earlier pending question. C5 remains partial for the full requested launch/all-resource populated-data contract. No credentials, real providers, dev/prod URLs, gates, thresholds or live QA checklist entries changed. Published CI/Rex remain separate evidence.

## Approved in-memory follow-up — 12 November 2026

Ran the existing full-context FHIR tenant-boundary, capability, SMART-discovery and role-gate suites against the repository's unique-per-context `jdbc:h2:mem:` test profile. `TestPostgresConfig` does not connect to PostgreSQL. Local servlet containers use ephemeral ports, bearer fixtures are generated in-process, and the OIDC decoder uses a local public key rather than provider discovery. No external identity-provider exchange occurs.

Added two parameterized cases to `FhirTenantBoundaryIT`: for every one of the 11 registered clinical providers, both read and search reject missing or signature-tampered HMS tokens with 401, even when the request supplies a valid hospital header. Each case first reads a real persisted in-tenant Patient with a valid signed token as a positive control.

The existing boundary cases additionally exercise populated Patient, Encounter, Condition, MedicationRequest and Immunization reads/searches, unknown-versus-foreign response parity, shared-patient MRN filtering, bound-hospital roles, super-admin pinning and locally signed OIDC-shaped JWTs through the real servlet/security chain. The tests use two hospitals in one organization; cross-organization FHIR isolation is not newly claimed. The role-gate suite uses MockMvc and proves authorization decisions, not HAPI resource dispatch.

Command from `C:/dev/hms-qa-c1`:

```powershell
./gradlew.bat :hospital-core:test --tests '*FhirTenantBoundaryIT' --tests '*FhirCapabilityStatementIT' --tests '*SmartConfigurationIT' --tests '*FhirRoleGateIT' --tests '*FhirServletStubContractIntegrationTest' -PtestForks=1 --console=plain
# Exit 0: 88 passed (1 capability, 46 stub contracts, 17 boundary including 2 new, 2 SMART discovery, 22 role-gate).
```

Portal `npm.cmd run lint`, `npm.cmd run format:check`, report Prettier check and `git diff --check` each exited 0. Java compiled with the selected tests; no Java formatter is configured.

Logs: `c5-inmemory-tests.log`, `c5-inmemory-lint.log`, `c5-inmemory-format.log` in the same local evidence directory. H2 logs warnings about cross-schema admission foreign-key DDL and platform-schema teardown. These do not fail the selected tests, but this run is not PostgreSQL migration/constraint validation. No production correction was needed.
