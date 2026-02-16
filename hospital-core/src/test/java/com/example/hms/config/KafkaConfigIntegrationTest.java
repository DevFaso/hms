package com.example.hms.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.payload.event.PlatformServiceEventPayload;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaConfigIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withPropertyValues(
            "spring.kafka.enabled=true",
            "spring.kafka.bootstrap-servers=localhost:9092",
            "spring.kafka.consumer.group-id=test-group",
            "app.kafka.enabled=true",
            "app.kafka.chat-topic=hospital.chat",
            "app.kafka.empi-identity-topic=hospital.empi.identity",
            "app.kafka.patient-movement-topic=hospital.patient.movement",
            "app.kafka.platform-registry-topic=hospital.platform.registry"
        )
        .withUserConfiguration(TestKafkaConfiguration.class);

    @Test
    void shouldExposePlatformRegistryTopicAndTemplate() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("platformRegistryTopic");
            assertThat(context).hasBean("platformRegistryKafkaTemplate");

            NewTopic topic = context.getBean("platformRegistryTopic", NewTopic.class);
            assertThat(topic.name()).isEqualTo("hospital.platform.registry");
            assertThat(topic.numPartitions()).isEqualTo(3);

            @SuppressWarnings("unchecked")
            KafkaTemplate<String, PlatformServiceEventPayload> template =
                (KafkaTemplate<String, PlatformServiceEventPayload>) context.getBean("platformRegistryKafkaTemplate");
            assertThat(template).isNotNull();
        });
    }

    @Configuration
    @EnableConfigurationProperties(KafkaProperties.class)
    @Import(KafkaConfig.class)
    static class TestKafkaConfiguration {
        // No-op configuration that imports the KafkaConfig for context testing
    }
}
