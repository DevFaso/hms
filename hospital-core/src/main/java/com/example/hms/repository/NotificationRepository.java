package com.example.hms.repository;

import com.example.hms.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByRecipientUsernameAndReadOrderByCreatedAtDesc(String recipientUsername, boolean read);

    List<Notification> findByRecipientUsernameAndMessageContainingIgnoreCaseOrderByCreatedAtDesc(String recipientUsername, String search);
    Page<Notification> findByRecipientUsernameAndReadAndMessageContainingIgnoreCase(String recipientUsername, boolean read, String search, org.springframework.data.domain.Pageable pageable);

    Page<Notification> findByRecipientUsername(String recipientUsername, Pageable pageable);
    Page<Notification> findByRecipientUsernameAndRead(String recipientUsername, boolean read, Pageable pageable);
    Page<Notification> findByRecipientUsernameAndMessageContainingIgnoreCase(String recipientUsername, String search, Pageable pageable);

    long countByReadFalse();

    long countByReadFalseAndCreatedAtBefore(LocalDateTime timestamp);
}
