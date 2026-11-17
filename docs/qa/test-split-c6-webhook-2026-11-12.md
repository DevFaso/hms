# C6: webhook receiver contract coverage

Brief: `test-split-codex-claude-2026-11-12.md`, C6. Executed 11 November 2026 (client date); filename follows the brief. Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`.

## Evidence

`WebhookReceiverContractIntegrationTest` joins the real dispatcher, HMAC signer and transport request serialization to a Spring `MockRestServiceServer` in-process receiver adapter. Four cases prove:

- A 202 response completes one delivery, records its timestamp and resets endpoint failures. The receiver independently calculates HMAC-SHA256 over the timestamp and exact received body and checks the event and delivery headers.
- A 503 remains pending; a sweep before the configured retry delay makes no additional attempt; a later 204 completes the same delivery with a fresh timestamp and signature.
- Repeated 500 responses exhaust the configured attempt budget, produce terminal `ERROR` (the implementation's dead-letter state), strike the endpoint once, and stop further sends.
- An HTTP loopback URL is refused before the receiver is reached and becomes terminal.

The repository and transaction manager are stubs. Candidate eligibility and claim state are simulated; these cases assert the dispatcher's configured cutoff but do not execute or prove the repository query, SQL claim atomicity, durability, concurrent delivery behavior or rollback. The retry policy is a fixed delay, not exponential backoff. No production values or thresholds changed.

The test replaces only the transport's private RestClient adapter using test reflection. The production URL-shape guard still runs. No sockets, providers, credentials, live URLs or databases are used; DNS mock interactions are explicitly forbidden. This does not prove production TLS, connection timeouts or DNS rebinding protection. The existing transport test class includes a network-facing case and was deliberately excluded.

## Verification

Run from the isolated `C:/dev/hms-qa-c1` worktree:

```powershell
./gradlew.bat :hospital-core:test --tests '*WebhookReceiverContractIntegrationTest' --tests '*WebhookDeliveryDispatchServiceTest' --tests '*WebhookSignerTest' -PtestForks=1 --console=plain
# Exit 0: 16 tests passed (4 new, 9 dispatch, 3 signer).
cd hospital-portal
npm.cmd run lint
# Exit 0.
npm.cmd run format:check
# Exit 0.
cd ..
git diff --check
# Exit 0.
```

Portal lint/format are repository checks and do not format or lint Java. No Java formatter/linter is configured; the touched Java source compiled and ran. Exact command logs: `C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/c6-{tests,lint,format}.log`.

Tests and this report only; no production changes. CI and Rex review remain separate evidence. No live QA checklist item is marked complete by this simulator coverage.
