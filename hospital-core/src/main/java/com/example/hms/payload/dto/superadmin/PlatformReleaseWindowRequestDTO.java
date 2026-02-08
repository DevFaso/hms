package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PlatformReleaseWindowRequestDTO {

    @NotBlank
    @Size(max = 120)
    private String name;

    @Size(max = 240)
    private String description;

    @NotBlank
    @Size(max = 60)
    private String environment;

    @NotNull
    @FutureOrPresent
    private LocalDateTime startsAt;

    @NotNull
    private LocalDateTime endsAt;

    private boolean freezeChanges;

    @Size(max = 120)
    private String ownerTeam;

    @Size(max = 255)
    private String notes;
}
