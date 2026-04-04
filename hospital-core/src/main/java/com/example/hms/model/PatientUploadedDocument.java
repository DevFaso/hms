package com.example.hms.model;

import com.example.hms.enums.PatientDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A document uploaded by a patient (or authorised proxy) through the patient portal.
 * This table stores metadata only; the actual file lives on the server filesystem.
 */
@Entity
@Table(name = "patient_uploaded_documents", schema = "clinical",
        indexes = {
                @Index(name = "idx_pat_doc_patient",     columnList = "patient_id"),
                @Index(name = "idx_pat_doc_type",        columnList = "patient_id, document_type"),
                @Index(name = "idx_pat_doc_uploaded_by", columnList = "uploaded_by_user_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PatientUploadedDocument extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_user_id", nullable = false)
    private User uploadedByUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 50)
    private PatientDocumentType documentType;

    /** Original filename shown to the user. */
    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    /** Server-side relative file path (e.g. /uploads/patient-documents/...). */
    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    /** Public-facing URL for downloading the file. */
    @Column(name = "file_url", length = 2048)
    private String fileUrl;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    /** Date the document was originally collected/issued (may differ from upload date). */
    @Column(name = "collection_date")
    private LocalDate collectionDate;

    @Column(name = "notes", length = 2048)
    private String notes;

    /** Soft-delete timestamp; null means the document is visible. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
