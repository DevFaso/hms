package com.example.hms.payload.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Sizes mirror the embedded {@code PlatformOwnership} columns, so an over-long value is a 400 with a field error, not a database failure. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformOwnershipDTO {

    @Size(max = 120)
    private String ownerTeam;

    @Email
    @Size(max = 255)
    private String ownerContactEmail;

    @Size(max = 120)
    private String dataSteward;

    @Size(max = 60)
    private String serviceLevel;
}
