package com.example.hms.payload.dto.globalsetting;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GlobalSettingRequestDTO(
    @NotBlank(message = "Setting key is required")
    @Size(max = 120, message = "Setting key must be 120 characters or less")
    String settingKey,

    @Size(max = 2000, message = "Setting value must be 2000 characters or less")
    String settingValue,

    @Size(max = 60, message = "Category must be 60 characters or less")
    String category,

    @Size(max = 255, message = "Description must be 255 characters or less")
    String description
) {
    @JsonCreator
    public GlobalSettingRequestDTO(
        @JsonProperty("settingKey") String settingKey,
        @JsonProperty("settingValue") String settingValue,
        @JsonProperty("category") String category,
        @JsonProperty("description") String description
    ) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.category = category;
        this.description = description;
    }
}
