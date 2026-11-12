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

This is servlet/component coverage with mocked tenancy decisions and empty data. It does not prove positive patient-data read/search mapping for every resource, SQL isolation, persisted cross-organisation rows, the Spring authentication filter chain, JWT cryptography, patient-context launch restriction, granted SMART scope enforcement, OAuth code/token exchange or a browser SMART launch. Advertised scopes are not proof that a grant is enforced.

Existing full-context FHIR/SMART ITs import `TestPostgresConfig`; they were not run under the current no-database instruction. The pending question about disposable in-memory test databases has not been answered; no database was started. C5 remains partial for the full requested data/launch contract. No credentials, providers, dev/prod URLs, gates, thresholds or live QA checklist entries changed. Published CI/Rex remain separate evidence.
