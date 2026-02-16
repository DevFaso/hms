package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Binary artifacts (DICOM images, PDF narratives, thumbnails) linked to an imaging report.
 */
@Entity
@Table(
    name = "imaging_report_attachments",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_imaging_attachment_report", columnList = "report_id")
    }
)
@IdClass(ImagingReportAttachmentId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = {"report", "position"})
@ToString(exclude = "report")
public class ImagingReportAttachment {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_imaging_attachment_report"))
    private ImagingReport report;

    @Id
    @Column(name = "position", nullable = false)
    private Integer position;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "storage_bucket", length = 120)
    private String storageBucket;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "dicom_object_uid", length = 64)
    private String dicomObjectUid;

    @Column(name = "viewer_url", length = 500)
    private String viewerUrl;

    @Column(name = "thumbnail_key", length = 255)
    private String thumbnailKey;

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "category", length = 80)
    private String category;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
