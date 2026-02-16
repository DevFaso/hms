package com.example.hms.service.platform.event;

import com.example.hms.config.KafkaProperties;
import com.example.hms.payload.event.PlatformServiceEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka-backed publisher for platform registry lifecycle events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaPlatformRegistryEventPublisher implements PlatformRegistryEventPublisher {

    private final ObjectProvider<KafkaTemplate<String, PlatformServiceEventPayload>> kafkaTemplateProvider;
    private final KafkaProperties kafkaProperties;

    @Override
    public void publish(PlatformServiceEventPayload payload) {
        if (payload == null) {
            return;
        }
        KafkaTemplate<String, PlatformServiceEventPayload> template = kafkaTemplateProvider.getIfAvailable();
        if (template == null) {
            log.debug("Kafka template unavailable; skipping platform registry event {}", payload.getEventType());
            return;
        }

        String topic = kafkaProperties.getPlatformRegistryTopic();
        template.send(topic, payload.eventKey(), payload).whenComplete((result, throwable) -> {
            if (throwable != null) {
                log.warn("Failed to publish platform registry event {} to topic {}: {}", payload.getEventType(), topic, throwable.getMessage());
            } else if (log.isDebugEnabled()) {
                log.debug("Published platform registry event {} to topic {} partition {} offset {}", payload.getEventType(),
                    result.getRecordMetadata().topic(), result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            }
        });
    }
}
