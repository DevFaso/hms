package com.example.hms.service.platform.event;

import com.example.hms.payload.event.PlatformServiceEventPayload;

/**
 * Publishes platform registry lifecycle events to downstream consumers (e.g., Kafka, audit log).
 */
public interface PlatformRegistryEventPublisher {

    void publish(PlatformServiceEventPayload payload);
}
