package com.example.hms.model.referral;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "obgyn_referral_messages",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_obgyn_message_referral", columnList = "referral_id"),
        @Index(name = "idx_obgyn_message_sent_at", columnList = "sent_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ObgynReferralMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referral_id", nullable = false, foreignKey = @ForeignKey(name = "fk_obgyn_message_referral"))
    private ObgynReferral referral;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_obgyn_message_sender"))
    private User sender;

    @Lob
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Lob
    @Column(name = "attachments", columnDefinition = "TEXT")
    private String attachmentsJson;

    @Builder.Default
    @Column(name = "read", nullable = false)
    private boolean read = false;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @PrePersist
    void onPersist() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }
}
