package com.example.hms.model;

import com.example.hms.enums.TreatmentOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
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

@Entity
@Table(
    name = "patient_surgical_history",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_surgical_history_patient", columnList = "patient_id"),
        @Index(name = "idx_surgical_history_hospital", columnList = "hospital_id"),
        @Index(name = "idx_surgical_history_date", columnList = "procedure_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PatientSurgicalHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_surgical_history_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_surgical_history_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_surgical_history_staff"))
    private Staff performedBy;

    @Column(name = "procedure_code", length = 50)
    private String procedureCode;

    @Column(name = "procedure_display", length = 255, nullable = false)
    private String procedureDisplay;

    @Column(name = "procedure_date")
    private LocalDate procedureDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 30)
    private TreatmentOutcome outcome;

    @Column(name = "location", length = 150)
    private String location;

    @Column(name = "source_system", length = 100)
    private String sourceSystem;

    @Column(name = "notes", length = 2048)
    private String notes;

    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;
}
