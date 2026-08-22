package com.example.hms.model;

import com.example.hms.enums.IntakeOutputCategory;
import com.example.hms.enums.IntakeOutputRoute;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
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
 * One measured fluid intake or output event (P3 #18, flowsheets/I&O).
 * <p>
 * The first NUMERIC I&O surface: until now "INTAKE_OUTPUT" existed only as a
 * free-text NursingTask category whose completion note captured no volumes.
 * Modeled on {@link com.example.hms.model.labor.LaborPartographEntry}: same
 * observation-time/late-entry quad and the same tenancy/attribution columns.
 */
@Entity
@Table(
    name = "intake_output_entries",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_io_entry_patient",  columnList = "patient_id"),
        @Index(name = "idx_io_entry_hospital", columnList = "hospital_id"),
        @Index(name = "idx_io_entry_time",     columnList = "observation_time")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "recordedByStaff", "documentedBy"})
public class IntakeOutputEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_io_entry_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_io_entry_hospital"))
    private Hospital hospital;

    /* ── Timepoint (house late-entry quad) ─────────────────────────────── */

    @Column(name = "observation_time", nullable = false)
    private LocalDateTime observationTime;

    @Column(name = "documented_at", nullable = false)
    private LocalDateTime documentedAt;

    @Column(name = "late_entry", nullable = false)
    @Builder.Default
    private boolean lateEntry = false;

    @Column(name = "original_entry_time")
    private LocalDateTime originalEntryTime;

    /* ── Measurement ───────────────────────────────────────────────────── */

    /** Derived from {@link #route} — persisted so totals GROUP BY stays a column scan. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 10)
    private IntakeOutputCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "route", nullable = false, length = 20)
    private IntakeOutputRoute route;

    @Column(name = "volume_ml", nullable = false)
    private Integer volumeMl;

    @Size(max = 500)
    @Column(name = "notes", length = 500)
    private String notes;

    /* ── Attribution ───────────────────────────────────────────────────── */

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_io_entry_staff"))
    private Staff recordedByStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documented_by_user_id",
        foreignKey = @ForeignKey(name = "fk_io_entry_user"))
    private User documentedBy;

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (documentedAt == null) {
            documentedAt = LocalDateTime.now();
        }
        if (observationTime == null) {
            observationTime = documentedAt;
        }
        if (route != null) {
            category = route.getCategory();
        }
        if (lateEntry && originalEntryTime == null) {
            originalEntryTime = observationTime;
        }
        if (notes != null) {
            notes = notes.trim();
        }
    }
}
