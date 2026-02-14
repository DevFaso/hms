package com.example.hms.payload.dto.discharge;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for follow-up appointments in discharge summaries
 * Part of Story #14: Discharge Summary Assembly
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FollowUpAppointmentDTO {

    @NotBlank(message = "Appointment type is required")
    @Size(max = 100)
    private String appointmentType; // POST_OP_CHECK, WOUND_CHECK, LAB_RECHECK, SPECIALTY_CONSULT

    @Size(max = 255)
    private String providerName;

    @Size(max = 100)
    private String specialty;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate appointmentDate;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime appointmentTime;

    @Size(max = 500)
    private String location;

    @Size(max = 50)
    private String phoneNumber;

    @Size(max = 1000)
    private String purpose;

    private Boolean isConfirmed;

    @Size(max = 100)
    private String confirmationNumber;

    private UUID appointmentId;

    @Size(max = 1000)
    private String specialInstructions;
}
