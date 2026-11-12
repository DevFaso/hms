# C9: message replay composition — partial

Scope: C9 message replay composition from the external planning brief `test-split-codex-claude-2026-11-12.md` (not included in this repository). Executed 11 November 2026 (client date); report dated 12 November 2026. Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`.

`IntegrationMessageReplayContractIntegrationTest` composes the actual message recorder and super-admin service over a map-backed repository stub. Four cases cover that a recorded failure produces a separate REPLAYED history row preserving correlation, organization, direction, type and payload; the original remains unchanged; successful and REPLAYED rows cannot be replayed; and a failed replay save surfaces as HTTP 500 rather than fabricated success. An explicitly named C9 characterization test records that replaying the same FAILED original twice currently creates two history rows; this pins a known limitation, not an idempotence guarantee.

## Remaining limitations

The implementation explicitly describes replay as symbolic: `SuperAdminIntegrationMessageServiceImpl.replay()` calls only `IntegrationMessageRecorder.recordReplay()`. It does not resend the envelope to a protocol adapter. The original FAILED row is retained, so calling replay again with that **original** ID can create another history row; rejection of the new REPLAYED row is not original-request idempotence. No connector delivery or receiver deduplication is demonstrated. C9 remains partial for actual replay/idempotence.

No new protocol behavior is invented in a test-coverage PR. Repository locking, retention races, transaction propagation and durability require separate database-backed validation; the stub's `findByIdForUpdate` is an ordinary map lookup. The existing recurring-failure fold has separate unit coverage and is not a connector idempotency mechanism.

## Verification

From isolated `C:/dev/hms-qa-c1`:

```powershell
./gradlew.bat :hospital-core:test --tests '*IntegrationMessageReplayContractIntegrationTest' --tests '*SuperAdminIntegrationMessageServiceImplTest' --tests '*IntegrationMessageRecorderTest' -PtestForks=1 --console=plain
# Original run: exit 0, 30 tests (3 new, 12 service, 15 recorder).
cd hospital-portal
npm.cmd run lint
# Exit 0.
npm.cmd run format:check
# Exit 0.
cd ..
git diff --check
# Exit 0.
```

Portal lint/format do not cover Java. No Java formatter/linter is configured; touched Java compiled and ran. The results above were recorded in the author's isolated checkout; the local logs are not repository artifacts. Consult this PR's CI results for independently accessible verification.

Tests/report only; no database, network, credentials, deployment or live QA checklist changes. CI and Rex remain separate checks.

Review follow-up: the original is reloaded and compared with its complete DTO snapshot, and the failure test verifies that replay actually calls save. The recorder deliberately swallows the original persistence exception, so the service cannot expose its root cause; asserting that cause would test a nonexistent contract. Default CI runs these classes through `:hospital-core:test`: `build.gradle` shards all class names and does not exclude the `IntegrationTest` suffix.

Follow-up verification on 12 November: `./gradlew.bat --no-daemon --offline :hospital-core:test -PtestForks=1 --tests "*IntegrationMessageReplayContractIntegrationTest" --tests "*SuperAdminIntegrationMessageServiceImplTest" --tests "*IntegrationMessageRecorderTest"` exited 0: 4 composition + 12 service + 15 recorder cases, plus 4 prerequisite observability checks; zero failures/errors. The dependency cache was isolated to avoid the earlier shared ZIP-filesystem access failure.
