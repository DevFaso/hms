package com.example.hms.model;

import com.example.hms.upload.UploadKind;
import com.example.hms.upload.UploadScanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One stored upload (V384): who sent it, where, the type its content showed,
 * its size, and the state of its malware check. The daily upload allowance of
 * a user and of a hospital is counted here, and a file whose check is not
 * {@link UploadScanStatus#CLEAN} is kept but not served.
 *
 * <p>The row holds no file name and no patient: {@code storageKey} is the key
 * in the storage port, the reference the owning row (a document, a photo, an
 * attachment) also carries.
 */
@Entity
@Table(
    name = "upload_records",
    schema = "support",
    uniqueConstraints = @UniqueConstraint(name = "uq_upload_records_storage_key", columnNames = "storage_key"),
    indexes = {
        @Index(name = "idx_upload_records_user_day", columnList = "uploaded_by_user_id, created_at"),
        @Index(name = "idx_upload_records_hospital_day", columnList = "hospital_id, created_at"),
        @Index(name = "idx_upload_records_scan_status", columnList = "scan_status, created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString
public class UploadRecord extends BaseEntity {

    /** The key in the storage port, such as {@code patient-documents/<name>}. */
    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_kind", nullable = false, length = 40)
    private UploadKind uploadKind;

    /** The media type the content showed; never the one the client declared. */
    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    /** The account that sent the file; null when the request carried none. */
    @Column(name = "uploaded_by_user_id")
    private UUID uploadedByUserId;

    /** The hospital the sender was acting at; null for a patient or a caller with none. */
    @Column(name = "hospital_id")
    private UUID hospitalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 20)
    private UploadScanStatus scanStatus;

    /** The scanner that gave the last verdict; {@code none} when no scanner is plugged in. */
    @Column(name = "scan_engine", length = 60)
    private String scanEngine;

    @Column(name = "scanned_at")
    private LocalDateTime scannedAt;
}
