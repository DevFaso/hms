package com.example.hms.model;

import com.example.hms.enums.LabQcEventLevel;
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
import jakarta.validation.constraints.NotNull;
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

@Entity
@Table(
    name = "qc_events",
    schema = "lab",
    indexes = {
        @Index(name = "idx_qc_events_hospital",    columnList = "hospital_id"),
        @Index(name = "idx_qc_events_testdef",     columnList = "test_definition_id"),
        @Index(name = "idx_qc_events_recorded_at", columnList = "recorded_at")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"testDefinition"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class LabQcEvent extends BaseEntity {

    @NotNull
    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    /** Instrument/analyzer identifier (e.g. device serial or logical name). */
    @Column(name = "analyzer_id", length = 100)
    private String analyzerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_definition_id",
        foreignKey = @ForeignKey(name = "fk_qc_event_testdef"))
    private LabTestDefinition testDefinition;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "qc_level", nullable = false, length = 30)
    private LabQcEventLevel qcLevel;

    @NotNull
    @Column(name = "measured_value", nullable = false, precision = 18, scale = 6)
    private BigDecimal measuredValue;

    @NotNull
    @Column(name = "expected_value", nullable = false, precision = 18, scale = 6)
    private BigDecimal expectedValue;

    @Column(name = "passed", nullable = false)
    @Builder.Default
    private boolean passed = false;

    @NotNull
    @Column(name = "recorded_at", nullable = false)
    @Builder.Default
    private LocalDateTime recordedAt = LocalDateTime.now();

    /** ID of the user who recorded this QC event. */
    @Column(name = "recorded_by_id")
    private UUID recordedById;

    @Column(name = "notes", length = 2048)
    private String notes;
}
