# C7: DHIS2 ADX push contract coverage

Brief: `test-split-codex-claude-2026-11-12.md`, C7. Executed 11 November 2026 (client date); filename follows the brief. Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`.

## Evidence

`DhisAdxPushContractIntegrationTest` joins the real export orchestrator, ADX XML writer, URL/address guard and HTTP client. The receiver is a Spring `MockRestServiceServer` adapter. Only DNS resolution, aggregation and persistence are stubbed; real URL-shape and address-classification code executes. Nineteen cases cover:

- Three receiver responses (200 with zero ignored, 200 with an ignored value, and a 401 error envelope without import counts). Each request has ADX content type, JSON Accept, synthetic PAT authentication and the run request ID. The receiver parses the actual XML and checks its namespace, facility, period, dataset, both aggregate counts, optional disaggregation and absence of patient fields. Actual response counts/status/body reach the persistence reconciliation boundary; no additional persistence calls are allowed.
- Four unsafe URL shapes, including HTTP, localhost, an internal name and embedded credentials, refused before resolution or HTTP with the expected destination-refusal message.
- Twelve public-looking names with simulated loopback, private, metadata, wildcard or CGNAT addresses, mixed public/private answers, IPv6 loopback/ULA/link-local and IPv4-mapped IPv6 answers, refused before HTTP with the expected destination-refusal message.

DNS is intercepted at `OutboundUrlGuard.resolvePublic`; byte-created answers pass through the actual `requireAllPublic` classifier. No real hostname is resolved. Numeric literal construction in the private-answer cases performs no DNS. Public-path HTTP is intercepted in-process; no sockets, live endpoints, credentials, environment reads or databases are used.

Persistence is mocked: this proves orchestration into reconciliation, not persisted SUCCESS/PARTIAL/FAILED rows or SQL transactions. Separate `Dhis2ExportRunPersistenceTest.finalizeReconciledFailed` cases assert that 401 and 503 responses remain FAILED even with positive import counts, fail the outbox and leave last-export time unchanged. The production HTTP connector, TLS, redirect behavior and check-to-connect DNS changes are outside this adapter test. Java normalizes mapped IPv6 literals to IPv4; those cases cover that normalized path. No production code or thresholds changed.

## Verification

Original author-reported verification before review corrections (11 contract cases), from an isolated checkout:

```powershell
./gradlew.bat :hospital-core:test --tests '*DhisAdxPushContractIntegrationTest' --tests '*DhisHttpClientTest' --tests '*DhisAdxXmlWriterTest' --tests '*DhisAdxExportServiceImplTest' -PtestForks=1 --console=plain
# Exit 0: 33 tests (11 new, 9 HTTP, 7 writer, 6 orchestrator).
cd hospital-portal
npm.cmd run lint
# Exit 0.
npm.cmd run format:check
# Exit 0.
cd ..
git diff --check
# Exit 0.
```

Portal lint/format do not cover Java. No Java formatter/linter is configured; the original touched Java source compiled and ran according to the report above. The developer-local log reference has been removed because it is not accessible to reviewers.

Review follow-up on 12 November 2026: the shared Gradle cache initially failed with a ZIP-filesystem `AccessDeniedException`. Verification then used an isolated dependency-cache copy; one offline attempt lacked the JaCoCo agent, followed by a successful normal dependency-resolution run. `./gradlew.bat --no-daemon :hospital-core:test -PtestForks=1 --tests com.example.hms.service.integration.DhisAdxPushContractIntegrationTest --tests com.example.hms.service.integration.impl.Dhis2ExportRunPersistenceTest` exited 0: 19 contract cases, 9 persistence cases and 4 prerequisite observability checks, zero failures/errors. Portal lint/format passed during preparation; changed report formatting and whitespace were rechecked. This validates the review corrections locally; new-head CI remains separate.

Tests and report only. CI/Rex remain separate checks; no live QA checklist line is ticked by this stub evidence.
