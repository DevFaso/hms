package com.example.hms.model.discharge;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Embeddable class for tracking scheduled follow-up appointments post-discharge
 * Part of Story #14: Discharge Summary Assembly
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class FollowUpAppointmentEntry {

    @Column(name = "appointment_type", nullable = false, length = 100)
    private String appointmentType; // POST_OP_CHECK, WOUND_CHECK, LAB_RECHECK, SPECIALTY_CONSULT

    @Column(name = "provider_name", length = 255)
    private String providerName;

    @Column(name = "specialty", length = 100)
    private String specialty;

    @Column(name = "appointment_date")
    private LocalDate appointmentDate;

    @Column(name = "appointment_time")
    private LocalDateTime appointmentTime;

    @Column(name = "location", length = 500)
    private String location;

    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    @Column(name = "purpose", length = 1000)
    private String purpose;

    @Column(name = "is_confirmed")
    private Boolean isConfirmed;

    @Column(name = "confirmation_number", length = 100)
    private String confirmationNumber;

    // Link to actual appointment if scheduled in system
    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Column(name = "special_instructions", length = 1000)
    private String specialInstructions;
}
