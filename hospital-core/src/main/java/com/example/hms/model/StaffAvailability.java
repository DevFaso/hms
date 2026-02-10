package com.example.hms.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

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
        if (staff != null && staff.getHospital() != null) {
            if (!Objects.equals(staff.getHospital().getId(), hospital != null ? hospital.getId() : null)) {
                throw new IllegalStateException("Availability hospital must match staff.hospital");
            }
        }
        // Time rules
        if (dayOff) {
            availableFrom = null;
            availableTo = null;
        } else {
            if (availableFrom == null || availableTo == null) {
                throw new IllegalStateException("availableFrom/availableTo are required when dayOff=false");
            }
            if (!availableTo.isAfter(availableFrom)) {
                throw new IllegalStateException("availableTo must be after availableFrom");
            }
        }
    }
}
