# C4: local MLLP transport coverage

Assignment: `test-split-codex-claude-2026-11-12.md`, C4. Executed 2026-11-11 (America/New_York); filename follows the assignment date. Base: `ec1682e15`.

Eight new integration cases use real TCP sockets on ephemeral loopback ports, MllpTcpServer, MllpFrameCodec, Hl7MessageDispatcher, Hl7v2MessageBuilder, IntegrationMessageRecorder and MllpOutboundSender. Clinical handlers and allowlist resolution are stubs; the message repository is a stateful in-process map-backed stub. No application or database starts.

- Byte-wise framed ADT A01 and ORU R01 input reaches the correct hospital/clinical handler and receives AA with the original control ID.
- Both clinical refusal paths return AE, never AA or the generic server-error ACK.
- A disallowed sender receives AR before any clinical handler runs. Repeated rejected frames pass through the real recorder and fold to one simulated row with attemptCount 2.
- A malformed header is rejected, and a subsequent valid connection still succeeds.
- A separate local receiver reads an outbound frame and returns AA or AE. The real sender preserves the body/control ID and distinguishes a negative acknowledgement from success.

## Local verification

- First run: `./gradlew.bat :hospital-core:test --tests '*MllpLocalTransportIntegrationTest' --tests '*MllpFrameCodecTest' --tests '*MllpOutboundSenderAckTest' --tests '*Hl7MessageDispatcherTest' --tests '*IntegrationMessageRecorderTest' -PtestForks=1 --console=plain`: exit 0, 70 passed (8 new +62 existing).
- After adding explicit handler/scope assertions: `./gradlew.bat :hospital-core:test --tests '*MllpLocalTransportIntegrationTest' --tests '*MllpInboundLabServiceImplTest' --tests '*MllpInboundAdtServiceImplTest' --tests '*MllpAllowedSenderServiceImplTest' -PtestForks=1 --console=plain`: exit 0, 95 passed (8 new +87 existing). Existing service tests include replay short-circuit/sender-identity cases.
- `npm run lint`, `npm run format:check`, `git diff --check`: exit 0. Frontend lint/format do not cover Java; no separate Java formatter/linter is configured. Java compilation passed.

Production sources, CI gates, thresholds and baselines are unchanged. No credentials, real providers, dev/prod URLs, database or Railway operations. All listening ports are assigned by the OS on loopback and closed by test cleanup. This proves application ACK/negative ACK behavior, not a transport-level ASCII NAK. Repository stubs do not prove SQL concurrency, persistence or transaction guarantees; no clinical record is created. This is simulator coverage, not live-device/deployed E2E, so the live QA checklist stays unticked. Keep draft; never ready, merge or deploy automatically.
