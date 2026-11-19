package com.example.hms.model;

import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * A key image (JPEG, PNG, WebP) or machine-exported PDF on an imaging study
 * (V316). It belongs to the order, and to the report it was attached to when
 * one was named; the bytes live in the storage port under
 * {@code imaging-images/}, served only through the authenticated imaging
 * image endpoint, under the same reading rules as the report itself.
 */
@Entity
@Table(
    name = "imaging_study_images",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_imaging_study_image_order", columnList = "imaging_order_id"),
        @Index(name = "idx_imaging_study_image_report", columnList = "imaging_report_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"imagingOrder", "imagingReport", "caption"})
public class ImagingStudyImage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "imaging_order_id", nullable = false)
    private ImagingOrder imagingOrder;

    /** The report the image was attached to; null for an image on the study itself. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imaging_report_id")
    private ImagingReport imagingReport;

    /** The order's hospital, copied so a scoped read needs no join. */
    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    /** "/uploads/imaging-images/&lt;uuid&gt;.&lt;ext&gt;": a server-built name, never the uploader's. */
    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    /** Optional; it can describe the patient's lesion, so it is encrypted. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "uploaded_by_user_id")
    private UUID uploadedByUserId;
}
