package com.example.hms.payload.dto;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response payload returned after a successful patient check-in (MVP 1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckInResponseDTO {

    private UUID appointmentId;
    private AppointmentStatus appointmentStatus;

    private UUID encounterId;
    private String encounterCode;
    private EncounterStatus encounterStatus;

    private UUID patientId;
    private String patientName;

    private LocalDateTime arrivalTimestamp;
    private String chiefComplaint;

    private String message;
}
