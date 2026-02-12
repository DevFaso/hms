package com.example.hms.service.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hms.config.KafkaProperties;
import com.example.hms.enums.platform.PlatformRegistryEventType;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.payload.event.PlatformServiceEventPayload;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class KafkaPlatformRegistryEventPublisherTest {

    @Mock
    private ObjectProvider<KafkaTemplate<String, PlatformServiceEventPayload>> kafkaTemplateProvider;

    @Mock
    private KafkaTemplate<String, PlatformServiceEventPayload> kafkaTemplate;

    private KafkaProperties kafkaProperties;

    private KafkaPlatformRegistryEventPublisher publisher;

    @BeforeEach
    void setUp() {
        kafkaProperties = new KafkaProperties();
        kafkaProperties.setPlatformRegistryTopic("platform.registry.events");
        publisher = new KafkaPlatformRegistryEventPublisher(kafkaTemplateProvider, kafkaProperties);
    }

    @Test
    void publishShouldIgnoreNullPayload() {
        publisher.publish(null);

        verifyNoInteractions(kafkaTemplateProvider);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publishShouldSkipWhenTemplateUnavailable() {
        when(kafkaTemplateProvider.getIfAvailable()).thenReturn(null);
        PlatformServiceEventPayload payload = samplePayload();

        publisher.publish(payload);

        verify(kafkaTemplateProvider).getIfAvailable();
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publishShouldSendEventWhenTemplateAvailable() {
        when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
        CompletableFuture<SendResult<String, PlatformServiceEventPayload>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(future);
        PlatformServiceEventPayload payload = samplePayload();

        publisher.publish(payload);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), eq(payload.eventKey()), eq(payload));
        String resolvedTopic = topicCaptor.getValue();
        assertThat(resolvedTopic).isEqualTo("platform.registry.events");

    }

    @Test
    void publishShouldLogWarningWhenSendFails(CapturedOutput output) {
        when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
        CompletableFuture<SendResult<String, PlatformServiceEventPayload>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(future);
        PlatformServiceEventPayload payload = samplePayload();

        publisher.publish(payload);

        future.completeExceptionally(new IllegalStateException("broker down"));

        assertThat(output.getOut())
            .contains("Failed to publish platform registry event")
            .contains(payload.getEventType().name());
    }

    private PlatformServiceEventPayload samplePayload() {
        return PlatformServiceEventPayload.builder()
            .eventType(PlatformRegistryEventType.ORGANIZATION_SERVICE_REGISTERED)
            .organizationId(UUID.randomUUID())
            .serviceType(PlatformServiceType.ANALYTICS)
            .status(PlatformServiceStatus.PENDING)
            .managedByPlatform(true)
            .occurredAt(Instant.parse("2025-01-01T00:00:00Z"))
            .build();
    }
}
