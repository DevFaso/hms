package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Structured measurement captured as part of an imaging report (e.g., lesion size, nodule density).
 */
@Entity
@Table(
    name = "imaging_report_measurements",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_imaging_measurement_report", columnList = "report_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = "report")
public class ImagingReportMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_imaging_measurement_report"))
    private ImagingReport report;

    @Column(name = "sequence_number")
    private Integer sequenceNumber;

    @Column(name = "label", length = 200)
    private String label;

    @Column(name = "region", length = 120)
    private String region;

    @Column(name = "plane", length = 80)
    private String plane;

    @Column(name = "modifier", length = 120)
    private String modifier;

    @Column(name = "numeric_value", precision = 18, scale = 6)
    private BigDecimal numericValue;

    @Column(name = "text_value", length = 500)
    private String textValue;

    @Column(name = "unit", length = 50)
    private String unit;

    @Column(name = "reference_min", precision = 18, scale = 6)
    private BigDecimal referenceMin;

    @Column(name = "reference_max", precision = 18, scale = 6)
    private BigDecimal referenceMax;

    @Column(name = "is_abnormal")
    private Boolean abnormal;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
