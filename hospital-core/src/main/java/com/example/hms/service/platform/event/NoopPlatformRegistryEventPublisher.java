package com.example.hms.service.platform.event;

import com.example.hms.payload.event.PlatformServiceEventPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Fallback publisher used when Kafka is disabled or unavailable.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(PlatformRegistryEventPublisher.class)
public class NoopPlatformRegistryEventPublisher implements PlatformRegistryEventPublisher {

    @Override
    public void publish(PlatformServiceEventPayload payload) {
        if (payload != null && log.isDebugEnabled()) {
            log.debug("No-op platform registry event for {}", payload.getEventType());
        }
    }
}
