package com.example.hms.payload.dto.prenatal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrenatalReminderRequestDTO {

    @NotNull
    private UUID appointmentId;

    /** Number of days before the appointment when the reminder should be sent. */
    @Min(0)
    private int daysBefore;

    private String customMessage;
}
