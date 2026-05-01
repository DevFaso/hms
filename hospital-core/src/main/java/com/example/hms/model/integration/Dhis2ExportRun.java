package com.example.hms.model.integration;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * One row per triggered DHIS2 export — manual or scheduled. Aggregates
 * across all per-value rows in {@link Dhis2ExportOutbox}.
 *
 * <p>{@link #requestId} is a UUID generated on creation that survives
 * retries; the orchestrator includes it as an idempotency hint to DHIS2
 * (when the target instance honours request IDs) and uses it to
 * correlate the import-summary response back to this run.
 */
@Entity
@Table(
    name = "dhis2_export_run",
    schema = "integration",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_dhis2_run_request_id",
            columnNames = "request_id")
    },
    indexes = {
        @Index(name = "idx_dhis2_run_hospital_started", columnList = "hospital_id, started_at"),
        @Index(name = "idx_dhis2_run_pending", columnList = "started_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "hospital")
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Dhis2ExportRun extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_dhis2_run_hospital"))
    private Hospital hospital;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 dataset UID must be 11 characters, alphanumeric, leading letter")
    @Column(name = "dataset_uid", nullable = false, length = 11)
    private String datasetUid;

    @NotBlank
    @Size(max = 16)
    @Column(name = "period_iso", nullable = false, length = 16)
    private String periodIso;

    /** Staff member who pressed the button. Null for scheduler-driven runs. */
    @Column(name = "triggered_by_staff_id")
    private UUID triggeredByStaffId;

    @NotNull
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private Dhis2ExportStatus status = Dhis2ExportStatus.PENDING;

    @Column(name = "value_count", nullable = false)
    @Builder.Default
    private int valueCount = 0;

    @Column(name = "skipped_count", nullable = false)
    @Builder.Default
    private int skippedCount = 0;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Size(max = 2048)
    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @NotNull
    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;
}
