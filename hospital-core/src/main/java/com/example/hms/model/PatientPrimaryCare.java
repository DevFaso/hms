package com.example.hms.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "patient_primary_care", schema = "clinical",
    uniqueConstraints = {
        // at most one *current* PCP per patient per hospital
        @UniqueConstraint(columnNames = {"patient_id", "hospital_id", "is_current"})
    },
    indexes = {@Index(columnList = "patient_id"), @Index(columnList = "hospital_id"), @Index(columnList = "assignment_id")}
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientPrimaryCare extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id")
    private UserRoleHospitalAssignment assignment;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private boolean current = true;

    @Column(length = 512)
    private String notes;
}
