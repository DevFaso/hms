package com.example.hms.service.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.example.hms.enums.platform.PlatformRegistryEventType;
import com.example.hms.payload.event.PlatformServiceEventPayload;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NoopPlatformRegistryEventPublisherTest {

    private final NoopPlatformRegistryEventPublisher publisher = new NoopPlatformRegistryEventPublisher();

    @Test
    void publishWithNullPayloadDoesNothing() {
        assertThatCode(() -> publisher.publish(null)).doesNotThrowAnyException();
    }

    @Test
    void publishWithPayloadLogsWhenDebugEnabled(CapturedOutput output) {
        Logger logger = (Logger) LoggerFactory.getLogger(NoopPlatformRegistryEventPublisher.class);
        Level previousLevel = logger.getLevel();
        try {
            logger.setLevel(Level.DEBUG);
            PlatformServiceEventPayload payload = PlatformServiceEventPayload.builder()
                .eventType(PlatformRegistryEventType.DEPARTMENT_LINKED_TO_SERVICE)
                .organizationId(UUID.randomUUID())
                .build();

            publisher.publish(payload);

            assertThat(output.getOut()).contains("No-op platform registry event for DEPARTMENT_LINKED_TO_SERVICE");
        } finally {
            logger.setLevel(previousLevel);
        }
    }
}
