# C8: instrument outbox simulator coverage — partial

Brief: `test-split-codex-claude-2026-11-12.md`, C8. Executed 11 November 2026 (client date). Base: `origin/develop` at `ec1682e152d507666148e58cd69933518b41a9fc`.

## Proven with a simulator

`InstrumentOutboxSimulatorIntegrationTest` joins the actual dispatch service, MLLP sender and frame codec to a loopback instrument on an OS-assigned ephemeral port. Eight cases cover AA/CA acceptance, AE/AR terminal refusal, missing-ACK retry after the configured delay, eventual successful ACK, exhaustion of the configured retry budget, and cancellation/entered-in-error refusal before an attempt.

Every transmitted frame is decoded by the simulator and compared with the queued synthetic OML payload. All accept/read/future waits are bounded. Connections, listener and executor are closed. Time advances through a stub Clock; no wall-clock sleeps or application retry-threshold changes are needed. Only test-local transport properties enable the sender and set ephemeral loopback connectivity.

Repository eligibility is simulated in memory. This proves service/transport composition, not SQL filtering, persisted state, transactions or simultaneous dispatch.

## Claim requirement remains open

At this base, `InstrumentOutboxDispatchService.dispatchPending()` calls `findDispatchable()` and sends each returned entity. `InstrumentOutboxRepository.findDispatchable()` is a SELECT with no lock or conditional claim; the service only increments attempts on its loaded entity. Unlike webhook dispatch, no atomic winner check exists before sending.

Two concurrent sweeps can therefore select the same PENDING row before either commits and both send it. `@Transactional` alone does not prevent that interleaving under ordinary read-committed isolation. The simulator cases do **not** prove a claim, exactly-once delivery or concurrency safety. C8 remains partial. A claim/locking correction needs a separate regression with repository transaction behavior verified; no production persistence changes are made under the current no-database constraint. Even a claim cannot by itself eliminate receiver-side duplicates after a lost ACK; receiver idempotency is separate.

## Verification

From isolated `C:/dev/hms-qa-c1`:

```powershell
./gradlew.bat :hospital-core:test --tests '*InstrumentOutboxSimulatorIntegrationTest' --tests '*InstrumentOutboxDispatchServiceTest' --tests '*MllpOutboundSenderAckTest' -PtestForks=1 --console=plain
# Exit 0: 23 tests (8 new simulator, 9 dispatch, 6 ACK parsing).
cd hospital-portal
npm.cmd run lint
# Exit 0.
npm.cmd run format:check
# Exit 0.
cd ..
git diff --check
# Exit 0.
```

Portal lint/format do not cover Java. No Java formatter/linter is configured; touched Java compiled and ran. Logs: `C:/Users/tiego/.codex/companions/memory/hms/test-split-2026-11-12/c8-{tests,lint,format}.log`.

Tests/report only; no database, live instrument, shared ports, credentials or provider calls. CI/Rex remain separate evidence. Do not tick the live instrument or claim checklist requirement.
