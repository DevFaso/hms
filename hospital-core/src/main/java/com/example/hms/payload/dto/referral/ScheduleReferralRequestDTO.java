package com.example.hms.payload.dto.referral;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleReferralRequestDTO {

    /**
     * The hospital's wall-clock appointment time, NOT a UTC instant: it is
     * copied into the booked Appointment's date and start time, which the
     * calendar, check-in, reminders and patient emails read as local time.
     */
    @NotNull
    @FutureOrPresent
    private LocalDateTime appointmentTime;

    @Size(max = 300)
    private String location;
}
