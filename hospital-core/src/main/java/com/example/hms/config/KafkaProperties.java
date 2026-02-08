package com.example.hms.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Custom Kafka application properties (prefix: app.kafka).
 * Provides metadata so IDEs recognize properties like app.kafka.chat-topic.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaProperties {

    /** Kafka topic name for chat messages */
    @NotBlank
    private String chatTopic;

    /** Kafka topic for EMPI identity command events */
    @NotBlank
    private String empiIdentityTopic;

    /** Kafka topic for patient movement and transfer notifications */
    @NotBlank
    private String patientMovementTopic;

    /** Kafka topic for platform registry lifecycle events */
    @NotBlank
    private String platformRegistryTopic;

    /** Whether Kafka integration is enabled (default true). */
    private boolean enabled = true;
}
