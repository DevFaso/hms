package com.example.hms.async;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flag for async dispense + lab processing via Kafka (roadmap
 * row 36, v2.0 / Performance).
 *
 * <p>Default {@code false} — the synchronous request-thread path
 * (today's behaviour) stays in place until the Kafka producer side
 * lands. Per the roadmap dependency, this row sits behind row 23
 * (ORU^R01 → LabResult persistence) — the producer plug-in only
 * makes sense once row 23 has soaked against real analyzer traffic.
 *
 * <p>The foundation pass ships only this configuration-properties
 * class and the feature flag. The Kafka consumer wiring,
 * {@code @KafkaListener} bodies, producer-side branches in
 * {@code MllpInboundLabServiceImpl} / {@code DispenseServiceImpl},
 * topic provisioning, and DLQ semantics are all the named row-36
 * follow-on. (Javadoc accuracy fix from PR #349 Copilot review —
 * the earlier wording referenced a "placeholder consumer wiring
 * class" that does not exist yet.)
 */
@ConfigurationProperties(prefix = "app.async.pipeline")
public class AsyncPipelineProperties {

    private boolean enabled = false;

    /** Kafka topic for ORU^R01 → LabResult settlement. */
    private String oruResultTopic = "hms.oru.result";

    /** Kafka topic for dispense settlement (stock decrement + audit). */
    private String dispenseSettlementTopic = "hms.dispense.settlement";

    /** Consumer-group id; per-env override recommended. */
    private String consumerGroup = "hms-async-pipeline";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOruResultTopic() {
        return oruResultTopic;
    }

    public void setOruResultTopic(String oruResultTopic) {
        this.oruResultTopic = oruResultTopic;
    }

    public String getDispenseSettlementTopic() {
        return dispenseSettlementTopic;
    }

    public void setDispenseSettlementTopic(String dispenseSettlementTopic) {
        this.dispenseSettlementTopic = dispenseSettlementTopic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }
}
