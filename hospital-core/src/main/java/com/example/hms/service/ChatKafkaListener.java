package com.example.hms.service;

import com.example.hms.config.KafkaProperties;
import com.example.hms.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Listens to Kafka chat topic and forwards messages to WebSocket subscribers.
 * Only active when app.kafka.enabled=true.
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChatKafkaListener {

    private static final Logger logger = LoggerFactory.getLogger(ChatKafkaListener.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final KafkaProperties kafkaProperties;

    public ChatKafkaListener(SimpMessagingTemplate messagingTemplate, KafkaProperties kafkaProperties) {
        this.messagingTemplate = messagingTemplate;
        this.kafkaProperties = kafkaProperties;
    }

    @KafkaListener(topics = "${app.kafka.chat-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void listen(ChatMessage chatMessage) {
        logger.info("Received message from Kafka topic '{}' destined for user '{}'", kafkaProperties.getChatTopic(), chatMessage.getRecipient().getUsername());
        messagingTemplate.convertAndSendToUser(
                chatMessage.getRecipient().getUsername(),
                "/topic/messages",
                chatMessage
        );
        logger.debug("Forwarded chat message to WebSocket for user '{}'", chatMessage.getRecipient().getUsername());
    }
}
