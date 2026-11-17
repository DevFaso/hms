package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Enables or disables an existing hospital link without deleting it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformServiceLinkUpdateRequestDTO {

    @NotNull
    private Boolean enabled;
}
