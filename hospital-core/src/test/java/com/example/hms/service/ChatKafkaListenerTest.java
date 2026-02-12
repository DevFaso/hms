package com.example.hms.service;

import com.example.hms.config.KafkaProperties;
import com.example.hms.model.ChatMessage;
import com.example.hms.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatKafkaListenerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private KafkaProperties kafkaProperties;

    @InjectMocks
    private ChatKafkaListener chatKafkaListener;

    // ───────────── helpers ─────────────

    private User buildUser(String username) {
        User u = new User();
        u.setUsername(username);
        return u;
    }

    private ChatMessage buildChatMessage(String recipientUsername) {
        User recipient = buildUser(recipientUsername);
        ChatMessage msg = new ChatMessage();
        msg.setRecipient(recipient);
        msg.setContent("Hello there");
        return msg;
    }

    // ═══════════════ Constructor ═══════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("constructs with dependencies injected")
        void constructsSuccessfully() {
            ChatKafkaListener listener = new ChatKafkaListener(messagingTemplate, kafkaProperties);
            assertNotNull(listener);
        }
    }

    // ═══════════════ listen ═══════════════

    @Nested
    @DisplayName("listen")
    class ListenTests {

        @Test
        @DisplayName("forwards message to correct WebSocket user destination")
        void forwardsToWebSocket() {
            when(kafkaProperties.getChatTopic()).thenReturn("chat-topic");
            ChatMessage msg = buildChatMessage("john_doe");

            chatKafkaListener.listen(msg);

            verify(messagingTemplate).convertAndSendToUser(
                eq("john_doe"),
                eq("/topic/messages"),
                eq(msg)
            );
        }

        @Test
        @DisplayName("calls getChatTopic for logging")
        void callsGetChatTopic() {
            when(kafkaProperties.getChatTopic()).thenReturn("my-chat-topic");
            ChatMessage msg = buildChatMessage("jane");

            chatKafkaListener.listen(msg);

            verify(kafkaProperties, atLeastOnce()).getChatTopic();
        }

        @Test
        @DisplayName("handles different recipient usernames")
        void differentRecipient() {
            when(kafkaProperties.getChatTopic()).thenReturn("topic");
            ChatMessage msg = buildChatMessage("alice_wonder");

            chatKafkaListener.listen(msg);

            verify(messagingTemplate).convertAndSendToUser(
                eq("alice_wonder"),
                eq("/topic/messages"),
                eq(msg)
            );
        }

        @Test
        @DisplayName("passes exact ChatMessage object to WebSocket template")
        void exactObjectPassed() {
            when(kafkaProperties.getChatTopic()).thenReturn("t");
            ChatMessage msg = buildChatMessage("bob");

            chatKafkaListener.listen(msg);

            verify(messagingTemplate).convertAndSendToUser(
                anyString(),
                anyString(),
                same(msg)
            );
        }
    }
}
