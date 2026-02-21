package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

@Entity
@Table(
    name = "staff_availability",
    schema = "hospital",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_staff_availability_staff_date", columnNames = {"staff_id", "date"})
    },
    indexes = {
        @Index(name = "idx_staff_availability_staff", columnList = "staff_id"),
        @Index(name = "idx_staff_availability_hospital", columnList = "hospital_id"),
        @Index(name = "idx_staff_availability_date", columnList = "date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class StaffAvailability extends BaseEntity {

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_availability_staff"))
    private Staff staff;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_availability_hospital"))
    private Hospital hospital;

    /** Specific calendar day this availability applies to. */
    @NotNull
    @Column(nullable = false)
    private LocalDate date;

    /** If dayOff=false, both times must be set and from < to. */
    private LocalTime availableFrom;
    private LocalTime availableTo;

    @Builder.Default
    @Column(nullable = false)
    private boolean dayOff = false;

    private String note;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @PrePersist
    @PreUpdate
    private void validate() {
        // Hospital integrity: availability must be for the staff's hospital
        if (staff != null && staff.getHospital() != null
                && !Objects.equals(staff.getHospital().getId(), hospital != null ? hospital.getId() : null)) {
            throw new IllegalStateException("Availability hospital must match staff.hospital");
        }
        // Time rules
        if (dayOff) {
            availableFrom = null;
            availableTo = null;
        } else {
            if (availableFrom == null || availableTo == null) {
                throw new IllegalStateException("availableFrom/availableTo are required when dayOff=false");
            }
            // Allow cross-midnight windows: availableTo < availableFrom means the window spans midnight
            if (availableTo.equals(availableFrom)) {
                throw new IllegalStateException("availableTo must differ from availableFrom");
            }
        }
    }
}
