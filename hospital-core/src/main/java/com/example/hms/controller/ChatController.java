package com.example.hms.controller;

import com.example.hms.model.ChatMessage;
import com.example.hms.model.User;
import com.example.hms.payload.dto.ChatConversationSummaryDTO;
import com.example.hms.payload.dto.ChatMessageRequestDTO;
import com.example.hms.payload.dto.ChatMessageResponseDTO;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.ChatMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.hms.config.KafkaProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/chat")
@Tag(name = "Chat History", description = "APIs for chat messaging and retrieving chat history between users")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatMessageService chatMessageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final KafkaTemplate<String, ChatMessage> kafkaTemplate; // may be null if Kafka disabled
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;

    private final KafkaProperties kafkaProperties;

 
    public ChatController(
            ChatMessageService chatMessageService,
            SimpMessagingTemplate messagingTemplate,
            ObjectProvider<KafkaTemplate<String, ChatMessage>> kafkaTemplate,
            ChatMessageRepository chatMessageRepository,
            UserRepository userRepository,
            KafkaProperties kafkaProperties
    ) {
        this.chatMessageService = chatMessageService;
        this.messagingTemplate = messagingTemplate;
    this.kafkaTemplate = kafkaTemplate.getIfAvailable(); // null when Kafka disabled
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.kafkaProperties = kafkaProperties;
    }

    // --- WebSocket Send ---
    @MessageMapping("/chat.sendMessage")
    public void sendWebSocketMessage(@Payload ChatMessage chatMessage) {
        User sender = userRepository.findById(chatMessage.getSender().getId())
                .orElseThrow(() -> new NoSuchElementException("Sender not found"));
        User recipient = userRepository.findById(chatMessage.getRecipient().getId())
                .orElseThrow(() -> new NoSuchElementException("Recipient not found"));

        chatMessage.setSender(sender);
        chatMessage.setRecipient(recipient);
        chatMessage.setTimestamp(LocalDateTime.now());

        chatMessageRepository.save(chatMessage);

    if (kafkaProperties.isEnabled() && kafkaTemplate != null) {
            try {
                String topic = kafkaProperties.getChatTopic();
                kafkaTemplate.send(topic, chatMessage.getRecipient().getUsername(), chatMessage);
                logger.info("Message sent to Kafka topic '{}' for user '{}'", topic, chatMessage.getRecipient().getUsername());
            } catch (RuntimeException e) {
                logger.error("Error sending message to Kafka: ", e);
            }
        } else {
        // Fallback: directly forward over WebSocket when Kafka disabled
        messagingTemplate.convertAndSendToUser(
            chatMessage.getRecipient().getUsername(),
            "/topic/messages",
            chatMessage
        );
        logger.debug("Kafka disabled; delivered message directly via WebSocket");
        }
    }

    @PostMapping("/send")
    @Tag(name = "Chat History", description = "APIs for chat messaging and retrieving chat history between users")
    @Operation(summary = "Send a chat message via REST", description = "Send a chat message from one user to another.")
    public ResponseEntity<ChatMessageResponseDTO> sendMessage(
            @Valid @RequestBody ChatMessageRequestDTO dto,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return new ResponseEntity<>(chatMessageService.sendMessage(dto, locale), HttpStatus.CREATED);
    }

    @PutMapping("/mark-read/{senderId}/{recipientId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF', 'PATIENT', 'RECEPTIONIST')")
    @Operation(summary = "Mark messages as read", description = "Mark all messages as read from sender to recipient.")
    public ResponseEntity<Void> markMessagesAsRead(
            @PathVariable UUID senderId,
            @PathVariable UUID recipientId,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        chatMessageService.markMessagesAsRead(senderId, recipientId, locale);
        return ResponseEntity.noContent().build();
    }

    // --- REST: All Conversations (Inbox) ---
    @GetMapping("/conversations/{userId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF', 'PATIENT', 'RECEPTIONIST')")
    @Operation(
            summary = "List all conversations for a user",
            description = "Returns conversation summaries (with the last message, participant, and unread count) for the given user."
    )
    public ResponseEntity<List<ChatConversationSummaryDTO>> getAllConversations(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<ChatConversationSummaryDTO> conversations = chatMessageService.getUserConversations(userId, page, size);
        return ResponseEntity.ok(conversations);
    }

    // --- REST: Paginated Chat History Between Two Users ---
    @GetMapping("/history/{user1Id}/{user2Id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF', 'PATIENT', 'RECEPTIONIST')")
    @Operation(
            summary = "Get paginated chat history between two users",
            description = "Returns chat messages exchanged between user1 and user2 in descending timestamp order."
    )
    public ResponseEntity<List<ChatMessageResponseDTO>> getPaginatedChatHistory(
            @PathVariable UUID user1Id,
            @PathVariable UUID user2Id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        List<ChatMessageResponseDTO> messages =
                chatMessageService.getChatHistory(user1Id, user2Id, page, size, locale);
        return ResponseEntity.ok(messages);
    }

    // --- REST: Search in Chat History Between Two Users ---
    @GetMapping("/history/{user1Id}/{user2Id}/search")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF', 'PATIENT', 'RECEPTIONIST')")
    @Operation(
            summary = "Search messages in chat history",
            description = "Searches for messages containing a keyword between two users."
    )
    public ResponseEntity<List<ChatMessageResponseDTO>> searchChatHistory(
            @PathVariable UUID user1Id,
            @PathVariable UUID user2Id,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<ChatMessageResponseDTO> messages = chatMessageService.searchMessages(user1Id, user2Id, keyword, page, size);
        return ResponseEntity.ok(messages);
    }

    // --- REST: All Messages for a User (Inbox) ---
    @GetMapping("/messages/{userId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF', 'PATIENT', 'RECEPTIONIST')")
    @Operation(
            summary = "Get all messages for a user",
            description = "Returns all messages where the user is either sender or recipient, optionally filtered by read status."
    )
    public ResponseEntity<List<ChatMessageResponseDTO>> getAllMessagesForUser(
            @PathVariable UUID userId,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<ChatMessageResponseDTO> messages = chatMessageService.getAllMessagesForUser(userId, unread, page, size);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/messages/by-sender-email")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF', 'PATIENT', 'RECEPTIONIST')")
    public ResponseEntity<List<ChatMessageResponseDTO>> getMessagesBySenderEmail(@RequestParam String email) {
        return ResponseEntity.ok(chatMessageService.getMessagesBySenderEmail(email));
    }

    @GetMapping("/messages/by-recipient-email")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF', 'PATIENT', 'RECEPTIONIST')")
    public ResponseEntity<List<ChatMessageResponseDTO>> getMessagesByRecipientEmail(@RequestParam String email) {
        return ResponseEntity.ok(chatMessageService.getMessagesByRecipientEmail(email));
    }

    @GetMapping("/messages/by-sender-username")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF', 'PATIENT', 'RECEPTIONIST')")
    public ResponseEntity<List<ChatMessageResponseDTO>> getMessagesBySenderUsername(@RequestParam String username) {
        return ResponseEntity.ok(chatMessageService.getMessagesBySenderUsername(username));
    }

    @GetMapping("/messages/by-recipient-username")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'STAFF', 'PATIENT', 'RECEPTIONIST')")
    public ResponseEntity<List<ChatMessageResponseDTO>> getMessagesByRecipientUsername(@RequestParam String username) {
        return ResponseEntity.ok(chatMessageService.getMessagesByRecipientUsername(username));
    }

    // Kafka listener moved to separate conditional component

}

