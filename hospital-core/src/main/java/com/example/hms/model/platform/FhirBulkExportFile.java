package com.example.hms.model.platform;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One NDJSON output file of a completed bulk-export job (P3 #24) — a
 * manifest line: {@code {type, url, count}}. The bytes live on local
 * disk under the job's directory and stream only through the
 * authenticated download endpoint; the file name is resolved from THIS
 * row, never from raw client input, so path traversal has nothing to
 * grab onto.
 */
@Entity
@Table(name = "fhir_bulk_export_files", schema = "platform")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "job")
public class FhirBulkExportFile extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_bulk_export_file_job"))
    private FhirBulkExportJob job;

    @Column(name = "resource_type", nullable = false, length = 60)
    private String resourceType;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "resource_count", nullable = false)
    @Builder.Default
    private int resourceCount = 0;
}
