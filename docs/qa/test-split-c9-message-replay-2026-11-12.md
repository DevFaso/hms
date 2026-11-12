# C9: message replay composition — partial

Brief: `test-split-codex-claude-2026-11-12.md`, C9. Executed 11 November 2026 (client date). Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`.

`IntegrationMessageReplayContractIntegrationTest` composes the actual message recorder and super-admin service over a map-backed repository stub. Three new cases prove that a recorded failure produces a separate REPLAYED history row preserving correlation, organization, direction, type and payload; the original remains unchanged; successful and REPLAYED rows cannot be replayed; and a failed replay save surfaces as HTTP 500 rather than fabricated success.

## Remaining limitations

The implementation explicitly describes replay as symbolic: `SuperAdminIntegrationMessageServiceImpl.replay()` calls only `IntegrationMessageRecorder.recordReplay()`. It does not resend the envelope to a protocol adapter. The original FAILED row is retained, so calling replay again with that **original** ID can create another history row; rejection of the new REPLAYED row is not original-request idempotence. No connector delivery or receiver deduplication is demonstrated. C9 remains partial for actual replay/idempotence.

No new protocol behavior is invented in a test-coverage PR. Repository locking, retention races, transaction propagation and durability require separate database-backed validation; the stub's `findByIdForUpdate` is an ordinary map lookup. The existing recurring-failure fold has separate unit coverage and is not a connector idempotency mechanism.

## Verification

From isolated `C:/dev/hms-qa-c1`:

```powershell
./gradlew.bat :hospital-core:test --tests '*IntegrationMessageReplayContractIntegrationTest' --tests '*SuperAdminIntegrationMessageServiceImplTest' --tests '*IntegrationMessageRecorderTest' -PtestForks=1 --console=plain
# Exit 0: 30 tests (3 new, 12 service, 15 recorder).
cd hospital-portal
npm.cmd run lint
# Exit 0.
npm.cmd run format:check
# Exit 0.
cd ..
git diff --check
# Exit 0.
```

Portal lint/format do not cover Java. No Java formatter/linter is configured; touched Java compiled and ran. Logs: `C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/c9-{tests,lint,format}.log`.

Tests/report only; no database, network, credentials, deployment or live QA checklist changes. CI and Rex remain separate checks.
