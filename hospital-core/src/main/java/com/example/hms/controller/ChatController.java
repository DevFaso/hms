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

    /**
     * Internal messaging is open to every authenticated hospital user
     * (2026-08-23 role audit, decision C3): enumerating roles here kept
     * locking out whichever staff role the list forgot. Access to CONTENT is
     * still gated per-thread inside ChatMessageService (sender/recipient
     * participant checks, incl. the attachment ownership gate from PR #482).
     */
    private static final String CHAT_ROLES = "isAuthenticated()";

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
    @PreAuthorize(CHAT_ROLES)
    @Tag(name = "Chat History", description = "APIs for chat messaging and retrieving chat history between users")
    @Operation(summary = "Send a chat message via REST", description = "Send a chat message from one user to another.")
    public ResponseEntity<ChatMessageResponseDTO> sendMessage(
            @Valid @RequestBody ChatMessageRequestDTO dto,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return new ResponseEntity<>(chatMessageService.sendMessage(dto, locale), HttpStatus.CREATED);
    }

    @PutMapping("/mark-read/{senderId}/{recipientId}")
    @PreAuthorize(CHAT_ROLES)
    @Operation(summary = "Mark messages as read", description = "Mark all messages as read from sender to recipient.")
    public ResponseEntity<Void> markMessagesAsRead(
            @PathVariable UUID senderId,
            @PathVariable UUID recipientId,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        chatMessageService.markMessagesAsRead(senderId, recipientId, locale);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/attachments/{attachmentId}/download")
    @PreAuthorize(CHAT_ROLES)
    @Operation(summary = "Download a chat attachment",
            description = "Authenticated, participant-checked streaming — only the carrying "
                + "message's sender or recipient may read the bytes. Attachment files have no "
                + "public static mapping.")
    public ResponseEntity<org.springframework.core.io.Resource> downloadAttachment(
            @PathVariable UUID attachmentId,
            org.springframework.security.core.Authentication authentication) {
        ChatMessageService.ChatAttachmentPayload payload =
            chatMessageService.downloadAttachment(attachmentId, authentication.getName());
        return ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType(payload.contentType()))
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + payload.displayName().replace("\"", "'") + "\"")
            .body(new org.springframework.core.io.FileSystemResource(payload.path()));
    }

    // --- REST: Unread badge count for the signed-in user ---
    /**
     * Total unread messages for the CALLER.
     *
     * <p>Deliberately takes no {@code userId}: this feeds the shell's
     * topbar badge on every page, and a path variable would make one
     * user's unread count readable by another. The principal is the
     * only input.
     *
     * <p>A scalar rather than reusing {@code /conversations/{userId}},
     * which returns last-message previews — the shell must not pull
     * message content it never renders just to draw a number.
     */
    @GetMapping("/unread-count")
    @PreAuthorize(CHAT_ROLES)
    @Operation(
            summary = "Total unread messages for the signed-in user",
            description = "Scalar count for the topbar badge. Scoped to the principal; takes no user id."
    )
    public ResponseEntity<java.util.Map<String, Long>> getMyUnreadCount(java.security.Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return userRepository.findByUsername(principal.getName())
                .map(user -> ResponseEntity.ok(java.util.Map.of(
                        "unreadCount", chatMessageRepository.countByRecipient_IdAndReadFalse(user.getId()))))
                // A principal with no User row has no messages: report zero
                // rather than erroring a badge that renders on every page.
                .orElseGet(() -> ResponseEntity.ok(java.util.Map.of("unreadCount", 0L)));
    }

    // --- REST: All Conversations (Inbox) ---
    @GetMapping("/conversations/{userId}")
    @PreAuthorize(CHAT_ROLES)
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
    @PreAuthorize(CHAT_ROLES)
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
    @PreAuthorize(CHAT_ROLES)
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
    @PreAuthorize(CHAT_ROLES)
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
    @PreAuthorize(CHAT_ROLES)
    public ResponseEntity<List<ChatMessageResponseDTO>> getMessagesBySenderEmail(@RequestParam String email) {
        return ResponseEntity.ok(chatMessageService.getMessagesBySenderEmail(email));
    }

    @GetMapping("/messages/by-recipient-email")
    @PreAuthorize(CHAT_ROLES)
    public ResponseEntity<List<ChatMessageResponseDTO>> getMessagesByRecipientEmail(@RequestParam String email) {
        return ResponseEntity.ok(chatMessageService.getMessagesByRecipientEmail(email));
    }

    @GetMapping("/messages/by-sender-username")
    @PreAuthorize(CHAT_ROLES)
    public ResponseEntity<List<ChatMessageResponseDTO>> getMessagesBySenderUsername(@RequestParam String username) {
        return ResponseEntity.ok(chatMessageService.getMessagesBySenderUsername(username));
    }

    @GetMapping("/messages/by-recipient-username")
    @PreAuthorize(CHAT_ROLES)
    public ResponseEntity<List<ChatMessageResponseDTO>> getMessagesByRecipientUsername(@RequestParam String username) {
        return ResponseEntity.ok(chatMessageService.getMessagesByRecipientUsername(username));
    }

    // Kafka listener moved to separate conditional component

}

