package com.example.hms.payload.dto.featureflag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FeatureFlagOverrideRequestDTO(
    @NotNull(message = "Feature flag enabled state is required")
    Boolean enabled,
    @Size(max = 255, message = "Description must be 255 characters or less")
    String description
) {
    @JsonCreator
    public FeatureFlagOverrideRequestDTO(
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("description") String description
    ) {
        this.enabled = enabled;
        this.description = description;
    }
}
