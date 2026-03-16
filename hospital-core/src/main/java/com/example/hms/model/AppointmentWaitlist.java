package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
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
import lombok.ToString;

import java.time.LocalDate;

@Entity
@Table(
    name = "appointment_waitlist",
    schema = "scheduling",
    indexes = {
        @Index(name = "idx_waitlist_hospital",   columnList = "hospital_id"),
        @Index(name = "idx_waitlist_department", columnList = "department_id"),
        @Index(name = "idx_waitlist_patient",    columnList = "patient_id"),
        @Index(name = "idx_waitlist_status",     columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"hospital", "department", "patient", "preferredProvider", "offeredAppointment"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class AppointmentWaitlist extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_waitlist_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_waitlist_department"))
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_waitlist_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preferred_provider_id",
        foreignKey = @ForeignKey(name = "fk_waitlist_provider"))
    private Staff preferredProvider;

    @Column(name = "requested_date_from")
    private LocalDate requestedDateFrom;

    @Column(name = "requested_date_to")
    private LocalDate requestedDateTo;

    /** ROUTINE | URGENT | STAT */
    @Column(name = "priority", nullable = false, length = 20)
    @Builder.Default
    private String priority = "ROUTINE";

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** WAITING | OFFERED | CLOSED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "WAITING";

    /** Appointment created when the receptionist offers a slot. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offered_appointment_id",
        foreignKey = @ForeignKey(name = "fk_waitlist_offered_appt"))
    private Appointment offeredAppointment;

    @Column(name = "created_by", length = 255)
    private String createdBy;
}
