package com.example.hms.repository;

import com.example.hms.model.ChatAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, UUID> {

    /** Hot path for the chat history view: load every attachment under a message in order. */
    List<ChatAttachment> findByMessage_IdOrderByCreatedAtAsc(UUID messageId);

    /** Batch-load attachments for a page of messages without N+1. */
    List<ChatAttachment> findByMessage_IdInOrderByMessage_IdAscCreatedAtAsc(Collection<UUID> messageIds);

    boolean existsByStorageKey(String storageKey);
}
