package com.example.hms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Custom Kafka application properties (prefix: app.kafka).
 * Provides metadata so IDEs recognize properties like app.kafka.chat-topic.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaProperties {

    /** Kafka topic name for chat messages */
    private String chatTopic = "hospital.chat.messages";

    /** Kafka topic for EMPI identity command events */
    private String empiIdentityTopic = "hospital.empi.identity";

    /** Kafka topic for patient movement and transfer notifications */
    private String patientMovementTopic = "hospital.patient.movement";

    /** Kafka topic for platform registry lifecycle events */
    private String platformRegistryTopic = "hospital.platform.registry";

    /** Whether Kafka integration is enabled (default true). */
    private boolean enabled = true;
}
