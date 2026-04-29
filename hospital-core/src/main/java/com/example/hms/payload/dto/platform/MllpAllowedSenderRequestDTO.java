package com.example.hms.payload.dto.platform;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record MllpAllowedSenderRequestDTO(
    @NotNull(message = "Hospital id is required")
    UUID hospitalId,

    @NotBlank(message = "Sending application is required")
    @Size(max = 180, message = "Sending application must be 180 characters or less")
    String sendingApplication,

    @NotBlank(message = "Sending facility is required")
    @Size(max = 180, message = "Sending facility must be 180 characters or less")
    String sendingFacility,

    @Size(max = 255, message = "Description must be 255 characters or less")
    String description,

    Boolean active
) {
    @JsonCreator
    public MllpAllowedSenderRequestDTO(
        @JsonProperty("hospitalId") UUID hospitalId,
        @JsonProperty("sendingApplication") String sendingApplication,
        @JsonProperty("sendingFacility") String sendingFacility,
        @JsonProperty("description") String description,
        @JsonProperty("active") Boolean active
    ) {
        this.hospitalId = hospitalId;
        this.sendingApplication = sendingApplication;
        this.sendingFacility = sendingFacility;
        this.description = description;
        this.active = active;
    }
}
