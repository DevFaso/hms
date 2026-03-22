package com.example.hms.model;

import com.example.hms.enums.FlowsheetType;
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
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Generic flowsheet entry for nursing assessments beyond vital signs.
 * Covers I&amp;O, pain, neuro checks, wound assessments, blood glucose, etc.
 */
@Entity
@Table(
    name = "flowsheet_entries",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_flowsheet_patient",   columnList = "patient_id"),
        @Index(name = "idx_flowsheet_hospital",  columnList = "hospital_id"),
        @Index(name = "idx_flowsheet_type",      columnList = "type"),
        @Index(name = "idx_flowsheet_recorded",  columnList = "recorded_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital"})
public class FlowsheetEntry extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_flowsheet_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_flowsheet_hospital"))
    private Hospital hospital;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private FlowsheetType type;

    /** Numeric value (e.g., pain score 0–10, intake mL, glucose mg/dL). */
    @Column(name = "numeric_value")
    private Double numericValue;

    /** Unit of measure (e.g., mL, mg/dL, score). */
    @Size(max = 20)
    @Column(name = "unit", length = 20)
    private String unit;

    /** Free-text value or structured assessment text. */
    @Column(name = "text_value", length = 2000)
    private String textValue;

    /** Structured sub-type (e.g., ORAL/IV for intake, URINE/DRAIN for output). */
    @Size(max = 40)
    @Column(name = "sub_type", length = 40)
    private String subType;

    @NotNull
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Size(max = 200)
    @Column(name = "recorded_by_name", length = 200)
    private String recordedByName;

    @Column(name = "notes", length = 1000)
    private String notes;
}
