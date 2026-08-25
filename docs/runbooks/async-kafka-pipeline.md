# Async dispense + lab via Kafka — foundation pass

**Status:** foundation pass shipped on `feat/v2.0-foundation-batch` (roadmap row 36).
**Scope today:** feature flag + property scaffolding only. No `@KafkaListener` bodies, no producer side, no topic provisioning. The actual fan-out lands when row 23 (ORU^R01 → LabResult persistence) has soaked against real analyzer traffic — that's the documented row-36 dependency.

---

## Feature flag

```
app.async.pipeline.enabled=${ASYNC_PIPELINE_ENABLED:false}
app.async.pipeline.oru-result-topic=hms.oru.result
app.async.pipeline.dispense-settlement-topic=hms.dispense.settlement
app.async.pipeline.consumer-group=hms-async-pipeline
```

Default OFF. When off, request handling stays on the existing synchronous request-thread path:

- `MllpInboundLabServiceImpl` writes the `LabResult` row + emits audit synchronously (today's behaviour).
- `DispenseServiceImpl` decrements stock + emits audit synchronously (today's behaviour).

When on (foundation pass): no behaviour change — the consumer-side `@KafkaListener` bodies are deferred. The flag exists so the topic + consumer-group names are env-configurable from day one and the producer-side branches (when they land) have a stable contract to target.

---

## Why deferred

The row's dependency is row 23 (ORU^R01 → LabResult persistence). Row 23 is `started` but not `completed` — the foundation pass landed without a long soak against real analyzer traffic. Switching the persistence path to async before that soak is **premature** because:

1. The synchronous path's latency profile is the baseline against which the async path must demonstrate improvement; without that baseline the async migration has nothing to argue against.
2. The error / retransmit semantics on ORU are still being calibrated against real Mindray / Sysmex traffic. Adding Kafka in the middle would entangle the analyzer-retransmit story with Kafka-consumer-retry semantics, making the eventual incident triage materially harder.
3. The DLQ / replay surface on row 23 (the `IntegrationMessageRecorder` wiring) is the appropriate retry surface for the synchronous path. Once that's stable + observed, the Kafka path can sit alongside as the long-tail bulk consumer.

The row-36 follow-on plan therefore waits on:

- Row 23 soak verdict — 14-day clean soak on hosted dev against a representative analyzer load.
- Decision: keep the synchronous path as the hot path and use Kafka only for the dispense-settlement fan-out (audit + stock + SMS), OR move ORU to Kafka outright. The decision drives the producer-side topology.

---

## Row-36 follow-on (in priority order)

- **Topic provisioning** under `infrastructure/kafka/` (Terraform or per-env compose for the existing `spring-kafka-test` Embedded Kafka path).
- **Producer-side**: `MllpInboundLabServiceImpl` and `DispenseServiceImpl` emit envelopes to the topics named in `AsyncPipelineProperties` when the flag is on; synchronous path stays as the fall-through when the flag is off OR when Kafka is unreachable.
- **Consumer-side**: `OruResultConsumer` and `DispenseSettlementConsumer` (`@KafkaListener` on the env-configurable topics) carrying the existing service-layer transactions; idempotency via the MSH-10 (ORU) + idempotency-key (dispense) the row-23 / row-94 work already added.
- **DLQ + retry policy**: `RetryTopicConfiguration` with the existing IntegrationMessageRecorder surface as the human-facing replay tool.
- **Observability**: per-topic `consumer_lag_seconds` Grafana panel + alert on lag > 60s for 5m (slot into the existing `hms.synthetic` alert group from row 43).
- **Multi-tenancy**: per-tenant topic prefixing OR partition-by-hospital-id (decision pending the row-39 ECOWAS residency outcome).

---

## Reference

- `hospital-core/src/main/java/com/example/hms/async/AsyncPipelineProperties.java`
- `hospital-core/build.gradle` (spring-kafka already on the dependency graph — row 36 reuses it)
- Row 23 producer-side starting point: `MllpInboundLabServiceImpl`
- Row 94 producer-side starting point: `DispenseServiceImpl` (idempotency_key partial unique index already in place)
