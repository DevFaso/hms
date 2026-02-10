package com.example.hms.model.referral;

import com.example.hms.enums.ReferralAttachmentCategory;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "obgyn_referral_attachments",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_obgyn_attachment_referral", columnList = "referral_id"),
        @Index(name = "idx_obgyn_attachment_category", columnList = "category")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ReferralAttachment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referral_id", nullable = false, foreignKey = @ForeignKey(name = "fk_obgyn_attachment_referral"))
    private ObgynReferral referral;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private ReferralAttachmentCategory category;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false, foreignKey = @ForeignKey(name = "fk_obgyn_attachment_uploader"))
    private User uploadedBy;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @PrePersist
    void onPersist() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
    }
}
