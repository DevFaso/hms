# C7: DHIS2 ADX push contract coverage

Brief: `test-split-codex-claude-2026-11-12.md`, C7. Executed 11 November 2026 (client date); filename follows the brief. Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`.

## Evidence

`DhisAdxPushContractIntegrationTest` joins the real export orchestrator, ADX XML writer, URL/address guard and HTTP client. The receiver is a Spring `MockRestServiceServer` adapter. Only DNS resolution, aggregation and persistence are stubbed; real URL-shape and address-classification code executes. Eleven cases cover:

- Three receiver responses (200 with zero ignored, 200 with an ignored value, and 401). Each request has ADX content type, JSON Accept, synthetic PAT authentication and the run request ID. The receiver parses the actual XML and checks its namespace, facility, period, dataset, both aggregate counts, optional disaggregation and absence of patient fields. Actual response counts/status reach the persistence reconciliation boundary.
- Four unsafe URL shapes, including HTTP, localhost, an internal name and embedded credentials, refused before resolution or HTTP.
- Four public-looking names with simulated loopback, private or metadata-address DNS answers, refused before HTTP.

DNS is intercepted at `OutboundUrlGuard.resolvePublic`; byte-created answers pass through the actual `requireAllPublic` classifier. No real hostname is resolved. Numeric literal construction in the private-answer cases performs no DNS. Public-path HTTP is intercepted in-process; no sockets, live endpoints, credentials, environment reads or databases are used.

Persistence is mocked: this proves orchestration into reconciliation, not persisted SUCCESS/PARTIAL/FAILED rows or SQL transactions. Existing unit coverage of reconciliation is separate. The production HTTP connector, TLS, redirect behavior and check-to-connect DNS changes are outside this adapter test. No production code or thresholds changed.

## Verification

From isolated `C:/dev/hms-qa-c1`:

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

Portal lint/format do not cover Java. No Java formatter/linter is configured; the touched Java source compiled and ran. Exact logs: `C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/c7-{tests,lint,format}.log`.

Tests and report only. CI/Rex remain separate checks; no live QA checklist line is ticked by this stub evidence.
