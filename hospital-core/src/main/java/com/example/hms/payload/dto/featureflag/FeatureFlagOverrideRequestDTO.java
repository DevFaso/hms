package com.example.hms.payload.dto.featureflag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FeatureFlagOverrideRequestDTO(
    @NotNull(message = "{featureFlagOverride.enabled.required}")
    Boolean enabled,
    @Size(max = 255, message = "{featureFlagOverride.description.size}")
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
