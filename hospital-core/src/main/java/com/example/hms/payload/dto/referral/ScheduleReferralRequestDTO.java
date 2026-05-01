package com.example.hms.payload.dto.referral;

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

    @NotNull
    private LocalDateTime appointmentTime;

    @Size(max = 300)
    private String location;
}
