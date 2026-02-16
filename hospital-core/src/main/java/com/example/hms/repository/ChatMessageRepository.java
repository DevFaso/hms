package com.example.hms.repository;

import com.example.hms.model.ChatMessage;
import com.example.hms.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    // 1. Paginated chat history between two users
    @Query("""
        SELECT m FROM ChatMessage m
        WHERE (m.sender = :user1 AND m.recipient = :user2)
           OR (m.sender = :user2 AND m.recipient = :user1)
        ORDER BY m.sentAt DESC
    """)
    Page<ChatMessage> findChatBetweenUsers(User user1, User user2, Pageable pageable);

    // 2. Latest message per conversation for a user (native, PostgreSQL safe version)
    @Query(value = """
        SELECT DISTINCT ON (LEAST(sender_id, recipient_id), GREATEST(sender_id, recipient_id))
            id, sender_id, recipient_id, content, timestamp, read
        FROM support.chat_messages
        WHERE sender_id = :userId OR recipient_id = :userId
        ORDER BY LEAST(sender_id, recipient_id), GREATEST(sender_id, recipient_id), sent_at DESC
        LIMIT :limit OFFSET :offset
    """, nativeQuery = true)
    List<Object[]> findLatestMessagesForUserNative(
            @Param("userId") UUID userId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    // 3. Count unread messages from one user to another
    int countBySenderAndRecipientAndReadFalse(User sender, User recipient);

    // 4. Find unread messages from sender to recipient
    @Query("""
        SELECT m FROM ChatMessage m
        WHERE m.sender = :sender AND m.recipient = :recipient AND m.read = false
    """)
    List<ChatMessage> findUnreadMessages(User sender, User recipient);

    // 5. Search messages between two users by keyword
    @Query(value = """
        SELECT * FROM support.chat_messages
        WHERE (
            (sender_id = :user1Id AND recipient_id = :user2Id)
            OR
            (sender_id = :user2Id AND recipient_id = :user1Id)
        )
        AND content ILIKE CONCAT('%', :keyword, '%')
        ORDER BY sent_at DESC
    """,
            countQuery = """
        SELECT COUNT(*) FROM support.chat_messages
        WHERE (
            (sender_id = :user1Id AND recipient_id = :user2Id)
            OR
            (sender_id = :user2Id AND recipient_id = :user1Id)
        )
        AND content ILIKE CONCAT('%', :keyword, '%')
    """,
            nativeQuery = true)
    Page<ChatMessage> searchMessagesBetweenUsers(
            @Param("user1Id") UUID user1Id,
            @Param("user2Id") UUID user2Id,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    // 6. All messages for a user (sent or received)
    @Query("""
        SELECT m FROM ChatMessage m
        WHERE m.sender.id = :userId OR m.recipient.id = :userId
        ORDER BY m.sentAt DESC
    """)
    Page<ChatMessage> findAllMessagesForUser(UUID userId, Pageable pageable);

    // 7. All unread messages for a user (sent or received)
    @Query("""
        SELECT m FROM ChatMessage m
        WHERE (m.sender.id = :userId OR m.recipient.id = :userId)
          AND m.read = false
        ORDER BY m.sentAt DESC
    """)
    Page<ChatMessage> findAllUnreadMessagesForUser(UUID userId, Pageable pageable);

    @Query(value = """
    SELECT DISTINCT ON (LEAST(sender_id, recipient_id), GREATEST(sender_id, recipient_id))
        id, sender_id, recipient_id, content, timestamp, read
    FROM support.chat_messages
    WHERE sender_id = :userId OR recipient_id = :userId
    ORDER BY LEAST(sender_id, recipient_id), GREATEST(sender_id, recipient_id), sent_at DESC
    LIMIT :limit OFFSET :offset
""", nativeQuery = true)
    List<Object[]> findLatestConversations(
            @Param("userId") UUID userId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    List<ChatMessage> findByRecipientEmail(String email);

    List<ChatMessage> findBySenderUsername(String username);

    List<ChatMessage> findByRecipientUsername(String username);

    List<ChatMessage> findBySenderEmail(String email);
}
