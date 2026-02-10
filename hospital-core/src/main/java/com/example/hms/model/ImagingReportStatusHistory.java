package com.example.hms.model;

import com.example.hms.enums.ImagingReportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
import java.util.UUID;

/**
 * Timeline of status transitions for an imaging report.
 */
@Entity
@Table(
    name = "imaging_report_status_history",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_imaging_history_report", columnList = "imaging_report_id"),
        @Index(name = "idx_imaging_history_order", columnList = "imaging_order_id"),
        @Index(name = "idx_imaging_history_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"imagingReport", "imagingOrder", "changedBy"})
public class ImagingReportStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "imaging_report_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_imaging_status_history_report"))
    private ImagingReport imagingReport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "imaging_order_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_imaging_status_history_order"))
    private ImagingOrder imagingOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ImagingReportStatus status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by",
        foreignKey = @ForeignKey(name = "fk_imaging_status_history_changed_by"))
    private Staff changedBy;

    @Column(name = "changed_by_name", length = 200)
    private String changedByName;

    @Column(name = "client_source", length = 120)
    private String clientSource;

    @Column(name = "notes", length = 1000)
    private String notes;

    @PrePersist
    void onCreate() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }
}
