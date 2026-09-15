package com.example.hms.payload.dto.platform;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record MllpAllowedSenderRequestDTO(
    @NotNull(message = "{mllpAllowedSender.hospitalId.required}")
    UUID hospitalId,

    @NotBlank(message = "{mllpAllowedSender.sendingApplication.required}")
    @Size(max = 180, message = "{mllpAllowedSender.sendingApplication.size}")
    String sendingApplication,

    @NotBlank(message = "{mllpAllowedSender.sendingFacility.required}")
    @Size(max = 180, message = "{mllpAllowedSender.sendingFacility.size}")
    String sendingFacility,

    @Size(max = 255, message = "{mllpAllowedSender.description.size}")
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
