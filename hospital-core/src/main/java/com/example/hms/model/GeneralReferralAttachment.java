package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Attachment entity for general referrals (lab results, imaging, notes, etc.)
 */
@Entity
@Table(name = "referral_attachments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneralReferralAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Parent referral
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referral_id", nullable = false)
    private GeneralReferral referral;

    /**
     * Storage key/path (S3 or file system)
     */
    @Column(nullable = false, length = 500)
    private String storageKey;

    /**
     * Display name for the attachment
     */
    @Column(nullable = false, length = 300)
    private String displayName;

    /**
     * Category of attachment
     */
    @Column(nullable = false, length = 50)
    private String category; // LAB_RESULT, IMAGING_REPORT, CONSULTATION_NOTE, CONSENT_FORM, PRESCRIPTION, OTHER

    /**
     * Content type (MIME type)
     */
    @Column(nullable = false, length = 100)
    private String contentType;

    /**
     * File size in bytes
     */
    @Column(nullable = false)
    private Long sizeBytes;

    /**
     * Staff member who uploaded the attachment
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_staff_id")
    private Staff uploadedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    /**
     * Description/notes about the attachment
     */
    @Column(length = 500)
    private String description;
}
