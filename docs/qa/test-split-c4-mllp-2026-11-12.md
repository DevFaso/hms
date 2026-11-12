# C4: local MLLP transport and recurrence coverage

Base: ec1682e152d507666148e58cd69933518b41a9fc. Updated 12 November 2026.

Ten transport cases use real TCP on ephemeral loopback ports, framing, dispatcher, message builder, recorder and outbound sender. Clinical handlers, allowlist and repository are stubs; no application/database starts. The repository stub keys occurrences by UUID and applies correlation/status/received-at cutoff when finding a prior failure.

Coverage:

- ADT/ORU acceptance returns AA with the original control ID and exact resolved-hospital handler arguments. Clinical refusal returns AE with the same handler verification and no unrelated handler calls.
- Repeated unknown sender folds into one FAILED row; two unknown senders sharing a control ID retain two rows. The 24-hour cutoff is asserted, and an older occurrence stays separate. Rejected events have no organization attribution; the event model has no hospital field.
- A malformed message produces a FAILED row with payload and correlation ID, then the listener accepts a valid connection. Current dispatcher code records these rejections synchronously before returning the ACK; the TCP server writes that returned ACK afterwards. Immediate row assertions therefore do not race asynchronous recording.
- Outbound AA/AE is distinguished through a separate loopback receiver.

Socket byte-wise writes use TCP_NODELAY but make no packet-segmentation claim. A codec case uses a plain InputStream whose single-byte and bulk reads supply at most one byte; its inherited readNBytes/transferTo cannot bypass the limit. It decodes two consecutive frames and EOF, with an explicit Unicode escape for multi-byte UTF-8 content. Accepted-row emptiness reflects mocked clinical recorders, not a claim that accepted traffic is never persisted.

Verification:

- `./gradlew.bat :hospital-core:test --tests '*MllpLocalTransportIntegrationTest' --tests '*MllpFrameCodecTest' --tests '*MllpOutboundSenderAckTest' --tests '*Hl7MessageDispatcherTest' --tests '*IntegrationMessageRecorderTest' -PtestForks=1 --console=plain`: exit 0, 73 passed (10 transport, 7 codec, 6 outbound ACK, 35 dispatcher, 15 recorder), before the final plain-stream fixture refinement. Initial fixture compilation failed and was corrected before this pass.
- Final touched-test command: `./gradlew.bat :hospital-core:test --tests '*MllpLocalTransportIntegrationTest' --tests '*MllpFrameCodecTest' -PtestForks=1 --console=plain`: exit 0, 17 passed.
- Portal lint/format on unchanged portal sources, report Prettier and whitespace checks: exit 0. Java compiled; no Java formatter is configured.

The pre-review 95-case run with lab/ADT/allowlist service suites is historical and was not repeated after test-only refinements. No production source changed. The test does not prove SQL persistence/concurrency/transactions, real devices, clinical outcomes or every receiving-facility header permutation. No real provider, credential, dev/prod, Railway, gate or baseline change. New-head CI is separate; no merge/deployment.
