package com.example.hms.payload.dto;

import com.example.hms.enums.AppointmentStatus;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentRequestDTO {

    private UUID id;

    @FutureOrPresent(message = "{appointment.appointmentDate.future}")
    private LocalDate appointmentDate;

    @NotNull(message = "{appointment.startTime.required}")
    private LocalTime startTime;

    /**
     * QA D1060: ignored on create, and on an edit that moves the visit: the
     * slot rules set the length. The service writes the resolved end here.
     */
    private LocalTime endTime;


    // Accept either ID or alternative identifier for patient
    private UUID patientId;
    private String patientUsername;
    private String patientEmail;

    // Accept either ID or alternative identifier for staff
    private UUID staffId;
    private String staffEmail;
    private String staffUsername;

    // Accept either ID or alternative identifier for hospital
    private UUID hospitalId;
    private String hospitalCode;
    private String hospitalName;

    // Accept either ID or alternative identifier for treatment

    // Optionally allow department to be specified directly
    private UUID departmentId;

    /**
     * QA D985: the recall this booking answers, when the desk books from the
     * recall list. Optional; the recall then moves to SCHEDULED.
     */
    private UUID recallId;
    private String departmentName;
    private String departmentCode;

    private AppointmentStatus status;

    private String reason;
    private String notes;

    /**
     * QA D1126: why the visit is cancelled or marked a no-show, required when
     * this edit moves the status to CANCELLED or NO_SHOW (the same rule as the
     * status action). Not the booking {@link #reason}.
     */
    @jakarta.validation.constraints.Size(max = 500)
    private String statusReason;
}
