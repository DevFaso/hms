package com.example.hms.repository;

import com.example.hms.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipientUsername = :username AND n.read = false")
    int markAllReadForUser(@Param("username") String username);
    List<Notification> findByRecipientUsernameAndReadOrderByCreatedAtDesc(String recipientUsername, boolean read);

    List<Notification> findByRecipientUsernameAndMessageContainingIgnoreCaseOrderByCreatedAtDesc(String recipientUsername, String search);
    Page<Notification> findByRecipientUsernameAndReadAndMessageContainingIgnoreCase(String recipientUsername, boolean read, String search, org.springframework.data.domain.Pageable pageable);

    Page<Notification> findByRecipientUsername(String recipientUsername, Pageable pageable);
    Page<Notification> findByRecipientUsernameAndRead(String recipientUsername, boolean read, Pageable pageable);
    Page<Notification> findByRecipientUsernameAndMessageContainingIgnoreCase(String recipientUsername, String search, Pageable pageable);

    long countByReadFalse();

    long countByRecipientUsernameAndReadFalse(String recipientUsername);

    long countByReadFalseAndCreatedAtBefore(LocalDateTime timestamp);
}
