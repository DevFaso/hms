package com.example.hms.service;

import com.example.hms.payload.dto.ChatConversationSummaryDTO;
import com.example.hms.payload.dto.ChatMessageRequestDTO;
import com.example.hms.payload.dto.ChatMessageResponseDTO;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface ChatMessageService {

    List<ChatMessageResponseDTO> getChatHistory(UUID user1Id, UUID user2Id, int page, int size, Locale locale);

    ChatMessageResponseDTO sendMessage(ChatMessageRequestDTO dto, Locale locale);

    void markMessagesAsRead(UUID senderId, UUID recipientId, Locale locale);

    List<ChatConversationSummaryDTO> getUserConversations(UUID userId, int page, int size);

    List<ChatMessageResponseDTO> searchMessages(UUID user1Id, UUID user2Id, String keyword, int page, int size);

    List<ChatMessageResponseDTO> getAllMessagesForUser(UUID userId, Boolean unread, int page, int size);

    List<ChatMessageResponseDTO> getMessagesBySenderEmail(String email);
    List<ChatMessageResponseDTO> getMessagesByRecipientEmail(String email);
    List<ChatMessageResponseDTO> getMessagesBySenderUsername(String username);
    List<ChatMessageResponseDTO> getMessagesByRecipientUsername(String username);

    /**
     * Resolve an attachment's bytes for authenticated streaming. Only the
     * carrying message's sender or recipient may download; anyone else —
     * or an unknown id — reads as not-found (no participant enumeration).
     * This is the ONLY way attachment bytes leave the server: the upload
     * tree has no public static mapping (the permitAll /uploads/** hole).
     */
    ChatAttachmentPayload downloadAttachment(java.util.UUID attachmentId, String requesterUsername);

    /** One downloadable attachment: on-disk path + the headers to serve it with. */
    record ChatAttachmentPayload(java.nio.file.Path path, String contentType, String displayName) { }

}
