package com.example.hms.model;

import com.example.hms.enums.ChatAttachmentKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Telehealth low-bandwidth attachment carried by a chat message.
 * One row = one PHOTO or one AUDIO clip. Storage path follows the
 * existing FileUploadService chart-attachments convention; SHA-256 is
 * captured at upload time for tamper detection.
 */
@Entity
@Table(
    name = "chat_attachments",
    schema = "support",
    indexes = {
        @Index(name = "idx_chat_attachments_message", columnList = "message_id, created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"message"})
public class ChatAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_chat_attachment_message"))
    private ChatMessage message;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ChatAttachmentKind kind;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "public_url", nullable = false, length = 1024)
    private String publicUrl;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    /** Audio length in seconds (1..90); null for PHOTO. */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
