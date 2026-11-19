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
import jakarta.persistence.UniqueConstraint;
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

/**
 * r63 D2304: the target mean and SD a hospital's laboratory director set for
 * one test at one control level. A QC run is judged against it (+/-2 SD,
 * +/-3 SD, standard Levey-Jennings practice); with no target a run is
 * recorded without a flag. Saving a target is the director's approval of it.
 */
@Entity
@Table(
    name = "qc_targets",
    schema = "lab",
    uniqueConstraints = @UniqueConstraint(name = "uq_qc_targets_hospital_test_level",
        columnNames = {"hospital_id", "test_definition_id", "qc_level"}),
    indexes = @Index(name = "idx_qc_targets_hospital", columnList = "hospital_id")
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"testDefinition"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class LabQcTarget extends BaseEntity {

    @NotNull
    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_definition_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_qc_targets_testdef"))
    private LabTestDefinition testDefinition;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "qc_level", nullable = false, length = 30)
    private LabQcEventLevel qcLevel;

    @NotNull
    @Column(name = "target_mean", nullable = false, precision = 18, scale = 6)
    private BigDecimal targetMean;

    @NotNull
    @Column(name = "target_sd", nullable = false, precision = 18, scale = 6)
    private BigDecimal targetSd;

    @Column(name = "lot_number", length = 100)
    private String lotNumber;

    @Column(name = "approved_by_id")
    private UUID approvedById;

    @Column(name = "approved_by_display", length = 255)
    private String approvedByDisplay;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
}
