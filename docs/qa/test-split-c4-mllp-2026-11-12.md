# C4: local MLLP transport coverage

Assignment: `test-split-codex-claude-2026-11-12.md`, C4. Executed 2026-11-11 (America/New_York); filename follows the assignment date. Base: `ec1682e15`.

Ten integration cases use real TCP sockets on ephemeral loopback ports, MllpTcpServer, MllpFrameCodec, Hl7MessageDispatcher, Hl7v2MessageBuilder, IntegrationMessageRecorder and MllpOutboundSender. Clinical handlers and allowlist resolution are stubs; the message repository is a stateful in-process map-backed stub. No application or database starts.

- ADT A01 and ORU R01 frames written byte-wise with TCP_NODELAY reach the correct hospital/clinical handler and receive AA with the original control ID. TCP packet boundaries remain controlled by the OS; deterministic single-byte stream reads are tested separately in the codec suite.
- Both clinical refusal paths return AE, never AA or the generic server-error ACK.
- A disallowed sender receives AR before any clinical handler runs. Repeated rejected frames pass through the real recorder and fold to one simulated row with attemptCount 2.
- Different rejected senders sharing a control ID retain distinct rows, each with one attempt and no organization attribution. A repeat after the 24-hour recurrence window creates a new row; the repository stub applies the actual cutoff and its value is asserted.
- A malformed header creates a FAILED row with its payload and a nonblank correlation ID; a subsequent valid connection still succeeds without losing that row.
- A separate local receiver reads an outbound frame and returns AA or AE. The real sender preserves the body/control ID and distinguishes a negative acknowledgement from success.

## Local verification

### Review follow-up — 12 November 2026

Addressed conversation review 6822983986. Rows are keyed by UUID instead of correlation, permitting multiple occurrences and safely surfacing malformed traffic. The lookup stub now enforces correlation, status and received-at cutoff; added sender-separation/window cases and a bound on the recorder's actual 24-hour cutoff argument. AE cases verify the exact clinical handler and no interaction with other handlers. Rejected/malformed rows explicitly carry no organization; the event entity has no separate hospital field. Accepted-row emptiness is explained as a consequence of mocked clinical recorders, not proof that accepted traffic is never persisted.

Added a deterministic codec case reading two frames (including UTF-8 content) from a stream whose bulk reads return at most one byte. Byte-wise socket writes alone do not prove packet segmentation, and the report no longer claims that they do.

The first compile attempt exited 1 (a removed Optional import was still needed and the repository mock needed class scope for the cutoff assertion). Corrected both fixture compilation issues. Final command: `./gradlew.bat :hospital-core:test --tests '*MllpLocalTransportIntegrationTest' --tests '*MllpFrameCodecTest' --tests '*MllpOutboundSenderAckTest' --tests '*Hl7MessageDispatcherTest' --tests '*IntegrationMessageRecorderTest' -PtestForks=1 --console=plain`, exit 0, 73 passed (10 transport, 7 codec, 6 outbound ACK, 35 dispatcher, 15 recorder). Portal lint/format, report Prettier and whitespace checks exit 0. No production changes. Logs: `c4-review-final-tests.log`, `c4-review-lint.log`, `c4-review-format.log`.

- First run: `./gradlew.bat :hospital-core:test --tests '*MllpLocalTransportIntegrationTest' --tests '*MllpFrameCodecTest' --tests '*MllpOutboundSenderAckTest' --tests '*Hl7MessageDispatcherTest' --tests '*IntegrationMessageRecorderTest' -PtestForks=1 --console=plain`: exit 0, 70 passed (8 new +62 existing).
- After adding explicit handler/scope assertions: `./gradlew.bat :hospital-core:test --tests '*MllpLocalTransportIntegrationTest' --tests '*MllpInboundLabServiceImplTest' --tests '*MllpInboundAdtServiceImplTest' --tests '*MllpAllowedSenderServiceImplTest' -PtestForks=1 --console=plain`: exit 0, 95 passed (8 new +87 existing). Existing service tests include replay short-circuit/sender-identity cases.
- `npm run lint`, `npm run format:check`, `git diff --check`: exit 0. Frontend lint/format do not cover Java; no separate Java formatter/linter is configured. Java compilation passed.

Production sources, CI gates, thresholds and baselines are unchanged. No credentials, real providers, dev/prod URLs, database or Railway operations. All listening ports are assigned by the OS on loopback and closed by test cleanup. This proves application ACK/negative ACK behavior, not a transport-level ASCII NAK. Repository stubs do not prove SQL concurrency, persistence or transaction guarantees; no clinical record is created. This is simulator coverage, not live-device/deployed E2E, so the live QA checklist stays unticked. Keep draft; never ready, merge or deploy automatically.
