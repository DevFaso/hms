package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
    name = "chat_messages",
    schema = "support",
    indexes = {
        @Index(name = "idx_chat_sender_time", columnList = "sender_id, sent_at"),
        @Index(name = "idx_chat_recipient_time", columnList = "recipient_id, sent_at"),
        @Index(name = "idx_chat_assignment", columnList = "assignment_id"),
        @Index(name = "idx_chat_unread_recipient", columnList = "recipient_id, is_read")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"sender", "recipient", "assignment"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ChatMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_chat_sender"))
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_chat_recipient"))
    private User recipient;

    @NotBlank
    @Size(max = 2048)
    @Column(name = "content", nullable = false, length = 2048)
    private String content;

    /** Delivery timestamp (separate from BaseEntity.createdAt if you ever copy/import). */
    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false)
    private LocalDateTime sentAt;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;


    private LocalDateTime timestamp;

    /** Context (role@hospital) of the sender at send time. Nullable for SUPER_ADMIN direct messages. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id",
        foreignKey = @ForeignKey(name = "fk_chat_assignment"))
    private UserRoleHospitalAssignment assignment;

    @PrePersist
    @PreUpdate
    private void validate() {
        if (sender == null || recipient == null) {
            throw new IllegalStateException("sender and recipient are required");
        }
        // Ensure the assignment belongs to the sender (if present)
        if (assignment != null && assignment.getUser() != null && sender.getId() != null
            && !Objects.equals(assignment.getUser().getId(), sender.getId())) {
            throw new IllegalStateException("Chat assignment must belong to the sender");
        }

    }


}
